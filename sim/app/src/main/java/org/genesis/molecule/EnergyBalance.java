package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeLabState;
import org.genesis.app.I18n;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Le bilan d'énergie, lisible d'un coup d'œil. En tête, la cohésion : l'énergie qu'il faut fournir pour séparer la
 * molécule en atomes isolés et neutres. Puis sa balance : chaque terme de GFN1-xTB comparé à sa valeur dans les atomes
 * isolés — ce qui lie (il abaisse l'énergie, en bleu) et ce qui coûte (en orange) ; la différence est la cohésion,
 * exactement. Les termes sont rangés par leur signe, pas par leur nom : l'électrostatique coûte dans l'eau (des charges
 * séparées, +1,4 eV) mais lie dans Au₆ (−1,6 eV). Enfin l'agitation : l'énergie cinétique des noyaux rapportée à la
 * cohésion, sur une échelle logarithmique (0,1 % → 100 %). L'énergie potentielle totale, très négative (surtout celle
 * des atomes eux-mêmes), reste en bas, en petit.
 */
final class EnergyBalance {
    /** Un terme de la balance : son nom, sa part (eV, négative : il lie), sa couleur. */
    record Term(String name, double value, Color colour) {}

    static final Color BINDS = Color.web("#5CC8FF"), COSTS = Color.web("#FF9A4D"), NET = Color.web("#EAF2FF");
    private static final Color BOUND = Color.web("#5BE39A"), WEAK = Color.web("#F2C46B"), UNBOUND = Color.web("#FF6F61"),
            GREY = Color.web("#8FA4C2"), TEXT = Color.web("#DCE8F7");
    private static final double ROW = 15, TERMS = 126;

    /** Les termes de la liaison (eV), par rapport aux atomes isolés : leur somme est l'énergie de liaison. */
    static List<Term> terms(MoleculeLabState s) {
        List<Term> terms = new ArrayList<>();
        terms.add(new Term("orbitales qui se recouvrent", s.getBand() - s.getBandAtoms(), null));
        terms.add(new Term("électrostatique des charges", s.getCoulomb2() + s.getCoulomb3() - s.getCoulombAtoms(), null));
        terms.add(new Term("répulsion des noyaux", s.getRepulsion(), null));
        terms.add(new Term("dispersion (London)", s.getDispersion(), null));
        terms.add(new Term("liaison halogène", s.getHalogen(), null));
        terms.add(new Term("entropie électronique (occupations lissées)", s.getEntropy() - s.getEntropyAtoms(), null));
        // Des bleus pour ce qui lie, des orangés pour ce qui coûte, du plus fort au plus faible.
        List<Term> binds = new ArrayList<>(), costs = new ArrayList<>();
        for (Term t : terms) {
            if (Math.abs(t.value) < 5e-4) continue;                         // un terme nul (pas d'halogène, 0 K)
            (t.value < 0 ? binds : costs).add(t);
        }
        binds.sort((a, b) -> Double.compare(a.value, b.value));
        costs.sort((a, b) -> Double.compare(b.value, a.value));
        List<Term> coloured = new ArrayList<>();
        for (int k = 0; k < binds.size(); k++) coloured.add(new Term(binds.get(k).name, binds.get(k).value, shade(BINDS, k)));
        for (int k = 0; k < costs.size(); k++) coloured.add(new Term(costs.get(k).name, costs.get(k).value, shade(COSTS, k)));
        return coloured;
    }

    private static Color shade(Color c, int rank) {
        return c.deriveColor(0, Math.max(.3, 1 - .2 * rank), Math.max(.45, 1 - .16 * rank), 1);
    }

    /** Le panneau, dessiné dans `canvas` ; sa hauteur suit ce qu'il y a à dire. */
    static void draw(Canvas canvas, MoleculeLabState s) {
        boolean alone = s.getAtomsCount() <= 1;
        List<Term> terms = alone ? List.of() : terms(s);
        List<String[]> extra = new ArrayList<>();
        if (s.getDynamics() == 2) {
            extra.add(new String[]{I18n.text("Énergie totale (conservée)"), MoleculeFormat.energy(s.getEnergy() + s.getKinetic())});
        }
        if (s.getDragAtom() >= 0) {
            extra.add(new String[]{I18n.text("Votre main"), MoleculeFormat.decimal(s.getTetherForceNn(), 2) + " nN · "
                    + MoleculeFormat.energy(s.getTetherEnergy())});
        }
        double w = canvas.getWidth();
        double balanceEnd = alone ? 46 : TERMS + ROW * (terms.size() - 1) + 18;
        double height = balanceEnd + (alone ? 0 : 54) + ROW * extra.size() + 22;
        if (Math.abs(canvas.getHeight() - height) > .5) canvas.setHeight(height);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, height);
        double cohesion = -s.getBinding();                                  // > 0 : la molécule tient
        // 1. La cohésion.
        Color state = alone ? GREY : cohesion > .5 ? BOUND : cohesion > 0 ? WEAK : UNBOUND;
        String verdict = alone ? "Un atome seul" : cohesion > .5 ? "Liée" : cohesion > 0 ? "À peine liée" : "Non liée";
        g.setFill(state.deriveColor(0, 1, 1, .18));
        g.fillOval(1, 6, 18, 18);
        g.setFill(state);
        g.fillOval(6, 11, 8, 8);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        g.fillText(I18n.text(verdict), 25, 20);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(GREY);
        g.fillText(I18n.text(alone ? "rien à lier : la cohésion naît des voisins"
                : cohesion > 0 ? "l'énergie à fournir pour la séparer en atomes" : "séparée en atomes, elle serait plus stable"), 2, 38);
        if (!alone) {
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
            g.setFill(NET);
            g.fillText(MoleculeFormat.decimal(Math.abs(cohesion), 2) + " eV", w - 2, 22);
            balance(g, w, terms, cohesion, state);
        }
        double y = balanceEnd;
        // 3. L'agitation : l'énergie cinétique des noyaux, rapportée à la cohésion (échelle log : 0,1 % … 100 %).
        if (!alone) {
            y += 14;
            double kinetic = s.getKinetic(), share = cohesion > 0 ? kinetic / cohesion : 1;
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 10.5));
            g.setFill(Color.web("#C6D4E8"));
            g.fillText(I18n.text("Agitation des noyaux") + "  ·  " + MoleculeFormat.decimal(s.getTemperature(), 0) + " K", 2, y);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFont(Font.font("Segoe UI", 10.5));
            g.fillText(MoleculeFormat.energy(kinetic), w - 2, y);
            double gy = y + 7, left = 2, width = w - 4;
            double at = share <= 0 ? 0 : Math.max(0, Math.min(1, (Math.log10(share) + 3) / 3));
            g.setFill(Color.web("#16202F"));
            g.fillRoundRect(left, gy, width, 6, 6, 6);
            Color heat = share < .02 ? BINDS : share < .1 ? WEAK : UNBOUND;
            g.setFill(heat.deriveColor(0, 1, 1, .9));
            g.fillRoundRect(left, gy, Math.max(4, width * at), 6, 6, 6);
            g.setStroke(Color.web("#04070D", .9));
            g.setLineWidth(1);
            for (int k = 1; k <= 2; k++) g.strokeLine(left + width * k / 3, gy, left + width * k / 3, gy + 6);
            g.setFont(Font.font("Segoe UI", 8.5));
            g.setFill(Color.web("#5F7391"));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("1 %", left + width / 3, gy + 16);
            g.fillText("10 %", left + 2 * width / 3, gy + 16);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", 9.5));
            g.setFill(GREY);
            String caption = cohesion <= 0 ? I18n.text("plus rien à défaire")
                    : s.getDynamics() == 0 && kinetic < 1e-6 ? I18n.text("relaxation : les noyaux descendent sans vitesse")
                    : MoleculeFormat.decimal(100 * share, share < .01 ? 2 : 1) + " % " + I18n.text("de la cohésion");
            g.fillText(caption, left, gy + 28);
            y = gy + 28;
        }
        // 4. Ce qui dépend du geste en cours, puis l'énergie potentielle totale.
        g.setFont(Font.font("Segoe UI", 10.5));
        for (String[] line : extra) {
            y += ROW;
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(TEXT);
            g.fillText(line[0], 2, y);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(line[1], w - 2, y);
        }
        y += ROW + 2;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setFill(Color.web("#6F82A0"));
        g.fillText(I18n.text("énergie potentielle totale") + "  " + MoleculeFormat.energy(s.getEnergy()), 2, y);
    }

    /**
     * La balance : ce qui lie part du zéro vers la gauche, ce qui coûte vers la droite. En dessous, ce qui reste — la
     * cohésion : ce qui lie moins ce qui coûte (une part de la barre bleue, de la longueur de l'orange, est « annulée »).
     */
    private static void balance(GraphicsContext g, double w, List<Term> terms, double cohesion, Color state) {
        double lie = 0, cost = 0;
        for (Term t : terms) {
            if (t.value < 0) lie -= t.value;
            else cost += t.value;
        }
        double left = 2, width = w - 4, span = Math.max(1e-9, lie + cost), zero = left + width * lie / span;
        double y = 58, bar = 64, ny = 82;
        g.setFont(Font.font("Segoe UI", 9.5));
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(BINDS);
        g.fillText(I18n.text("ce qui lie") + "  " + MoleculeFormat.decimal(lie, 2) + " eV", left, y);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(COSTS);
        g.fillText(I18n.text("ce qui coûte") + "  " + MoleculeFormat.decimal(cost, 2) + " eV", left + width, y);
        double x = zero;
        for (Term t : terms) {
            if (t.value >= 0) continue;
            double length = width * -t.value / span;
            g.setFill(t.colour);
            g.fillRoundRect(x - length + .5, bar, Math.max(1, length - 1), 12, 4, 4);
            x -= length;
        }
        x = zero;
        for (Term t : terms) {
            if (t.value < 0) continue;
            double length = width * t.value / span;
            g.setFill(t.colour);
            g.fillRoundRect(x + .5, bar, Math.max(1, length - 1), 12, 4, 4);
            x += length;
        }
        // Ce qui reste : de l'extrémité de ce qui lie, raccourcie de ce qui coûte, jusqu'au zéro.
        double net = width * Math.abs(cohesion) / span;
        double from = cohesion > 0 ? zero - net : zero, cancelled = width * Math.min(lie, cost) / span;
        g.setFill(state.deriveColor(0, 1, 1, .9));
        g.fillRoundRect(from, ny, Math.max(1.5, net), 6, 6, 6);
        g.setStroke(COSTS.deriveColor(0, 1, 1, .55));
        g.setLineWidth(1);
        g.setLineDashes(2, 2);
        if (cohesion > 0) g.strokeLine(left + 1, ny + 3, left + cancelled - 1, ny + 3);
        else g.strokeLine(left + 1, ny + 3, zero - 1, ny + 3);
        g.setLineDashes();
        g.setStroke(NET.deriveColor(0, 1, 1, .85));
        g.strokeLine(zero, bar - 3, zero, ny + 9);
        g.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 9.5));
        g.setFill(state);
        String rest = (cohesion > 0 ? I18n.text("reste : la cohésion") : I18n.text("il manque")) + "  "
                + MoleculeFormat.decimal(Math.abs(cohesion), 2) + " eV";
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(rest, cohesion > 0 ? Math.max(left, from) : left, ny + 19);
        // Les termes, du plus liant au plus coûteux.
        y = TERMS;
        for (Term t : terms) {
            g.setFill(t.colour);
            g.fillRoundRect(2, y - 8, 8, 8, 3, 3);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setFill(TEXT);
            g.fillText(I18n.text(t.name), 16, y);
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(MoleculeFormat.signed(t.value, Math.abs(t.value) < .1 ? 3 : 2) + " eV", w - 2, y);
            y += ROW;
        }
    }
}
