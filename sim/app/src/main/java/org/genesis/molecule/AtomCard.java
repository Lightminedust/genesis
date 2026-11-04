package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeAtom;
import org.genesis.app.I18n;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * La carte du survol d'un atome, dans la vue 3D : ce que le calcul en dit, dessiné plutôt qu'écrit.
 * <ul>
 *   <li>la tuile de l'élément (numéro atomique, symbole, masse) et la molécule dont il fait partie ;</li>
 *   <li>sa charge sur une jauge de −1 à +1 e (magenta : il a gagné des électrons, cyan : il en a perdu) ;</li>
 *   <li>sa force nette dans un cadran, tournée comme la vue ;</li>
 *   <li>ses voisins : la liaison (un, deux, trois traits ; des points s'ils ne sont pas liés), la distance, et une barre
 *       pour ce que la paire lui fait (bleu : elle l'attire, orange : elle le repousse ; échelle logarithmique).</li>
 * </ul>
 * Les mêmes forces sont tracées sur la scène, en flèches depuis l'atome (MoleculeScene.drawHover).
 */
final class AtomCard {
    static final double W = 272;
    private static final double ROW = 17;
    private static final Color CARD = Color.web("#070D18"), EDGE = Color.web("#2A3B52"), TEXT = Color.web("#DCE8F7"),
            GREY = Color.web("#8FA4C2"), DIM = Color.web("#5E718E");

    private AtomCard() {
    }

    /** Dessine la carte de l'atome i ; la toile prend la hauteur qu'il faut. */
    static void draw(Canvas canvas, MoleculeAtom atom, MoleculeScene scene, int i, boolean confined) {
        List<MoleculeScene.Pair> near = AtomMap.neighbours(scene, i);
        int rows = Math.min(5, near.size());
        double h = 112 + (rows > 0 ? 20 + rows * ROW : 0) + (confined ? 14 : 0) + 22;
        if (Math.abs(canvas.getWidth() - W) > .5) canvas.setWidth(W);
        if (Math.abs(canvas.getHeight() - h) > .5) canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, W, h);
        g.setFill(CARD.deriveColor(0, 1, 1, .94));
        g.fillRoundRect(0, 0, W, h, 12, 12);
        g.setStroke(EDGE);
        g.setLineWidth(1);
        g.strokeRoundRect(.5, .5, W - 1, h - 1, 12, 12);
        g.setLineCap(StrokeLineCap.ROUND);
        // La tuile de l'élément.
        Color element = MoleculeScene.elementColor(atom.getZ());
        g.setFill(element.deriveColor(0, 1, 1, .16));
        g.fillRoundRect(10, 10, 44, 44, 9, 9);
        g.setStroke(element.deriveColor(0, 1, 1, .85));
        g.setLineWidth(1.2);
        g.strokeRoundRect(10, 10, 44, 44, 9, 9);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 8.5));
        g.setFill(GREY);
        g.fillText(String.valueOf(atom.getZ()), 14, 20);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        g.setFill(element.deriveColor(0, 1, 1.15, 1));
        g.fillText(MoleculeView.nuclide(atom.getSymbol(), atom.getMassNumber()), 32, 39);
        g.setFont(Font.font("Segoe UI", 8));
        g.setFill(GREY);
        g.fillText(MoleculeFormat.decimal(atom.getMassU(), 3), 32, 51);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        g.setFill(TEXT);
        g.fillText(atom.getSymbol() + (i + 1) + "  ·  " + MoleculeFormat.element(atom.getZ()), 64, 26);
        g.setFont(Font.font("Segoe UI", 10.5));
        g.setFill(GREY);
        g.fillText(molecule(scene, i), 64, 44);
        // La charge : une jauge de −1 à +1 e, remplie depuis le neutre.
        double q = atom.getCharge(), y = 74;
        double x0 = 64, x1 = W - 74, mid = (x0 + x1) / 2;
        label(g, I18n.text("charge"), 10, y + 4);
        g.setFill(Color.web("#132238"));
        g.fillRoundRect(x0, y - 3, x1 - x0, 7, 7, 7);
        float[] c = MoleculeScene.chargeColour(q);
        Color tint = Color.color(c[0], c[1], c[2]);
        double reach = Math.max(-1, Math.min(1, q)) * (x1 - x0) / 2;
        g.setFill(tint.deriveColor(0, 1, 1, .9));
        g.fillRoundRect(Math.min(mid, mid + reach), y - 3, Math.abs(reach), 7, 7, 7);
        g.setStroke(Color.web("#E6EEFA", .5));
        g.setLineWidth(1);
        g.strokeLine(mid, y - 6, mid, y + 7);
        g.setFont(Font.font("Segoe UI", 8));
        g.setFill(DIM);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("−1", x0 + 4, y + 16);
        g.fillText("0", mid, y + 16);
        g.fillText("+1", x1 - 5, y + 16);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
        g.setFill(tint);
        g.fillText(MoleculeFormat.signed(q, 2) + " e", x1 + 8, y + 4);
        g.setFont(Font.font("Segoe UI", FontPosture.ITALIC, 9.5));
        g.setFill(GREY);
        String gained = Math.abs(q) < .05 ? I18n.text("presque neutre")
                : I18n.text(q < 0 ? "a gagné" : "a perdu") + " " + MoleculeFormat.decimal(Math.abs(q), 2) + " e⁻";
        g.fillText(gained, x1 + 8, y + 17);
        // La force nette : un cadran tourné comme la vue, sa flèche, sa valeur et vers qui elle tire.
        y = 104;
        double[] net = i < scene.netForce.length && scene.netForce[i] != null ? scene.netForce[i] : new double[3];
        double magnitude = Math.sqrt(net[0] * net[0] + net[1] * net[1] + net[2] * net[2]);
        double dx = 22, dy = y - 2, r = 11;
        g.setStroke(EDGE.brighter());
        g.setLineWidth(1);
        g.strokeOval(dx - r, dy - r, 2 * r, 2 * r);
        double[] right = scene.camera.right(), up = scene.camera.up();
        if (magnitude > .02) {
            double fx = net[0] * right[0] + net[1] * right[1] + net[2] * right[2];
            double fy = -(net[0] * up[0] + net[1] * up[1] + net[2] * up[2]);
            double len = Math.hypot(fx, fy), k = len > 1e-9 ? (r - 1.5) * Math.min(1, len / magnitude + .35) / len : 0;
            MoleculeScene.arrow(g, dx - fx * k * .2, dy - fy * k * .2, dx + fx * k, dy + fy * k, AtomMap.NET, 1.6);
        } else {
            g.setFill(AtomMap.NET);
            g.fillOval(dx - 2, dy - 2, 4, 4);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
        g.setFill(AtomMap.NET);
        g.fillText(I18n.text("force nette") + "  " + MoleculeFormat.decimal(magnitude, 2) + " eV/Å", 40, y + 2);
        String toward = magnitude < .02 ? I18n.text("à l'équilibre : les forces s'annulent") : towards(scene, i, net);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(GREY);
        if (!toward.isEmpty()) g.fillText(toward, 40, y + 15);
        y = 128;
        if (confined) {
            g.setFont(Font.font("Segoe UI", FontPosture.ITALIC, 9.5));
            g.setFill(Color.web("#FFB48A"));
            g.fillText(I18n.text("charge tenue à −2,5 e (contrainte des anions)"), 10, y);
            y += 14;
        }
        // Les voisins.
        if (rows > 0) {
            g.setStroke(EDGE);
            g.setLineWidth(.8);
            g.strokeLine(10, y - 6, W - 10, y - 6);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
            g.setFill(GREY);
            g.fillText(I18n.text("VOISINS"), 10, y + 6);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(I18n.text("ce que la paire lui fait (eV/Å)"), W - 10, y + 6);
            g.setTextAlign(TextAlignment.LEFT);
            y += 22;
            double biggest = .05;
            for (int k = 0; k < rows; k++) biggest = Math.max(biggest, norm(AtomMap.pairForce(scene, near.get(k), i)));
            for (int k = 0; k < rows; k++) {
                MoleculeScene.Pair p = near.get(k);
                int other = p.a() == i ? p.b() : p.a();
                neighbour(g, scene, i, other, p, y, biggest);
                y += ROW;
            }
        }
        g.setFont(Font.font("Segoe UI", FontPosture.ITALIC, 9));
        g.setFill(DIM);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(I18n.text("clic : sa carte (09) · clic droit : sa molécule"), 10, h - 9);
    }

    /** Une ligne de voisin : le type de liaison, le voisin, la distance, la barre de la force que la paire exerce. */
    private static void neighbour(GraphicsContext g, MoleculeScene scene, int i, int other, MoleculeScene.Pair p, double y,
                                  double biggest) {
        MoleculeScene.Bond bond = scene.bond(i, other);
        boolean linked = scene.linked(i, other);
        // Le glyphe : 1, 2, 3 traits (comme les brins de la scène), des points sans liaison.
        g.setStroke(Color.web("#C9D6E8", .9));
        g.setLineWidth(1.2);
        if (linked && bond != null) {
            int strokes = MoleculeScene.strokes(bond.order());
            for (int s = 0; s < strokes; s++) {
                double off = (s - (strokes - 1) / 2.0) * 3.2;
                if (strokes == 2 && s == 1 && MoleculeScene.partial(bond.order())) g.setLineDashes(2, 2);
                g.strokeLine(10, y - 4 + off, 26, y - 4 + off);
                g.setLineDashes();
            }
        } else {
            g.setFill(Color.web("#C9D6E8", .6));
            for (int s = 0; s < 4; s++) g.fillOval(10 + s * 4.5, y - 5, 1.8, 1.8);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        g.setFill(MoleculeScene.elementColor(scene.z[other]).deriveColor(0, .8, 1.25, 1));
        g.fillText(scene.symbols[other] + (other + 1), 33, y);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(TEXT);
        g.fillText(MoleculeFormat.decimal(p.distance(), 2) + " Å", 72, y);
        double[] f = AtomMap.pairForce(scene, p, i);
        double force = norm(f);
        boolean pulls = p.total() >= 0;                      // le long de l'axe, comme partout : + attire
        double x0 = 122, x1 = W - 48;
        double share = Math.log1p(force / .02) / Math.log1p(biggest / .02);
        g.setFill(Color.web("#132238"));
        g.fillRoundRect(x0, y - 7, x1 - x0, 5, 5, 5);
        g.setFill((pulls ? AtomMap.ATTRACT : AtomMap.REPEL).deriveColor(0, 1, 1, .9));
        g.fillRoundRect(x0, y - 7, Math.max(2, (x1 - x0) * share), 5, 5, 5);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10));
        g.setFill(pulls ? AtomMap.ATTRACT : AtomMap.REPEL);
        g.fillText((pulls ? "← " : "→ ") + MoleculeFormat.decimal(force, 2), W - 10, y);
    }

    /** « dans : eau » — l'espèce nommée dont l'atome fait partie, sinon la formule de sa molécule, ou « atome seul ». */
    static String molecule(MoleculeScene scene, int i) {
        for (MoleculeScene.Species s : scene.species) {
            for (int a : s.atoms()) {
                if (a != i) continue;
                if (s.atoms().length < 2) return I18n.text("atome seul");
                String label = MoleculeFormat.names(s.label());
                int dot = label.indexOf(" · ");
                return I18n.text("dans") + " " + (dot < 0 ? label : label.substring(dot + 3) + "  " + label.substring(0, dot));
            }
        }
        int[] members = scene.members(i);
        return members.length < 2 ? I18n.text("atome seul") : I18n.text("dans") + " " + MoleculeCard.formula(scene, members);
    }

    /** Vers qui tire la force nette, quand elle est presque dans la direction d'un voisin lié. */
    private static String towards(MoleculeScene scene, int i, double[] net) {
        double magnitude = norm(net), best = 0;
        int along = -1;
        for (int j = 0; j < scene.positions.length; j++) {
            if (j == i || !scene.linked(i, j)) continue;
            double[] a = scene.positions[i], b = scene.positions[j];
            double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
            double c = (d[0] * net[0] + d[1] * net[1] + d[2] * net[2]) / (norm(d) * magnitude);
            if (Math.abs(c) > Math.abs(best)) {
                best = c;
                along = j;
            }
        }
        if (along < 0 || Math.abs(best) < .75) return "";
        return I18n.text(best > 0 ? "vers" : "loin de") + " " + scene.symbols[along] + (along + 1);
    }

    private static void label(GraphicsContext g, String text, double x, double y) {
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        g.setFill(GREY);
        g.fillText(text.toUpperCase(I18n.locale()), x, y);
    }

    static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
