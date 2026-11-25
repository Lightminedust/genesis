package org.genesis.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * La marque de Genesis (assets/brand) : le G_ et le logotype GENESIS_, lus tels quels dans leurs SVG — un G blanc, ses
 * deux franges décalées (cyan à gauche, violette à droite) et le curseur violet d'un terminal. Les tracés sont gardés en
 * unités de la police (la ligne de base en y = 0, les capitales jusqu'à y = −740) ; chaque tracé est découpé en ses
 * parties (une lettre, le curseur, leurs franges), rangées par case : les écrans qui animent la marque (le démarrage,
 * l'attente d'un laboratoire) font apparaître, décalent ou font clignoter chaque partie à leur guise.
 *
 * <p>Les traits du G_ sont des glitchs figés : chaque frange est le blanc décalé de quelques unités (14 pour le G_, 12
 * pour le logotype, lus dans les SVG), et un trait est une tranche horizontale d'une frange qui a glissé plus loin
 * (dans le G_ : la cyan de 46 et 34 unités vers la gauche, la violette de 52 vers la droite). Animée, la marque glitche
 * pour de bon : par bouffées, de nouvelles tranches glissent (une frange, ou tout le dessin déchiré), puis tout revient.
 */
public final class Brand {
    /** Les couleurs de la marque. */
    public static final Color CYAN = Color.web("#38bdf8"), VIOLET = Color.web("#7c3aed"), CURSOR = Color.web("#8b5cf6"),
            INK = Color.WHITE, NIGHT = Color.web("#07070c");
    /** Le curseur clignote comme celui d'un terminal : une période (s), allumé la première moitié. */
    public static final double BLINK_S = 1.1;

    // La lecture des SVG : déclarée avant les œuvres, lues à l'initialisation de la classe.
    private static final Pattern PATH = Pattern.compile("<path\\s([^>]*)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    /** Le G_ : la marque seule (l'icône, la barre de chargement). */
    public static final Art MARK = load("/brand/logo/genesis-g_-blanc.svg");
    /** GENESIS_ : le logotype. */
    public static final Art LOGOTYPE = load("/brand/logo/genesis-logotype-blanc.svg");

    private Brand() {
    }

    /** Le rôle d'un calque : frange cyan, frange violette, le blanc des lettres, le curseur. */
    public enum Role { CYAN, VIOLET, INK, CURSOR }

    /** Une partie d'un calque : son tracé (unités de la police), sa boîte, sa case (0 = la première lettre). */
    public record Part(String path, double minX, double maxX, double minY, double maxY, int slot) {
    }

    /** Un calque : son rôle, sa couleur d'origine, ses parties de gauche à droite. */
    public record Layer(Role role, Color color, List<Part> parts) {
    }

    /**
     * Une œuvre : ses calques dans l'ordre du dessin, sa boîte, son nombre de cases (lettres et curseur) et l'écart de ses
     * franges au blanc (unités) : la frange cyan est le blanc décalé de cyanDx, la violette de violetDx.
     */
    public record Art(List<Layer> layers, double minX, double minY, double width, double height, int slots,
                      double cyanDx, double violetDx) {
        public Layer layer(Role role) {
            for (Layer layer : layers) if (layer.role() == role) return layer;
            throw new IllegalArgumentException(role.name());
        }

        /** L'écart d'un calque au blanc : celui de sa frange, 0 pour le blanc et le curseur. */
        public double fringeDx(Role role) {
            return role == Role.CYAN ? cyanDx : role == Role.VIOLET ? violetDx : 0;
        }

        /** La largeur de l'œuvre pour une hauteur donnée (px). */
        public double widthFor(double height) {
            return width * height / this.height;
        }
    }

    /** L'œuvre en nœuds JavaFX, haute de `height` px, son coin en haut à gauche en (0, 0) ; le curseur allumé. */
    public static Node node(Art art, double height) {
        double s = height / art.height();
        Group inner = new Group();
        for (Layer layer : art.layers()) {
            for (Part part : layer.parts()) {
                SVGPath shape = new SVGPath();
                shape.setContent(part.path());
                shape.setFillRule(FillRule.EVEN_ODD);
                shape.setFill(layer.color());
                inner.getChildren().add(shape);
            }
        }
        inner.getTransforms().addAll(new Scale(s, s, 0, 0), new Translate(-art.minX(), -art.minY()));
        Group out = new Group(inner);
        out.getStyleClass().add("brand");
        return out;
    }

    /** Remplit une partie, dans le repère courant (unités de la police), de la couleur `color` à l'opacité `alpha`. */
    public static void fill(GraphicsContext g, Part part, Color color, double alpha) {
        if (alpha <= 0) return;
        g.setFill(color.deriveColor(0, 1, 1, Math.min(1, alpha)));
        g.setFillRule(FillRule.EVEN_ODD);
        g.beginPath();
        g.appendSVGPath(part.path());
        g.fill();
    }

    /** Le curseur est-il allumé à l'instant `t` (s) ? */
    public static boolean cursorOn(double t) {
        return (t % BLINK_S + BLINK_S) % BLINK_S < BLINK_S / 2;
    }

    // ================================================================== les glitchs

    /** Ce qui varie d'une case à l'autre quand on anime la marque : sa présence, sa place, l'écart de plus de ses franges. */
    @FunctionalInterface
    public interface Look {
        /** L'opacité de la case (0 : absente). */
        double alpha(int slot);

        /** Son décalage (unités) : le curseur qui avance. */
        default double dx(int slot) {
            return 0;
        }

        /** L'écart de plus de ses franges (unités) : une lettre qui vient d'arriver. */
        default double spread(int slot) {
            return 0;
        }
    }

    /**
     * Une tranche horizontale déplacée par un glitch : entre y0 et y1 (unités), la frange `role` glisse de dx ; si role
     * vaut INK, toute la tranche glisse (le blanc, ses franges, le curseur) : une déchirure. `slot` = −1 : toute la ligne.
     */
    public record Slice(Role role, double y0, double y1, double dx, int slot) {
    }

    /** Les traits du G_ tels que son SVG les dessine : trois tranches de franges qui ont glissé (unités). */
    public static final List<Slice> MARK_TRAITS = List.of(
            new Slice(Role.CYAN, -560, -526, -46, -1),
            new Slice(Role.VIOLET, -380, -346, 52, -1),
            new Slice(Role.CYAN, -70, -50, -34, -1));

    /** Une bouffée de glitch environ toutes les GLITCH_EVERY s, de 3 à 5 pas de GLITCH_STEP s. */
    public static final double GLITCH_EVERY = 3.2, GLITCH_STEP = 0.055;

    /**
     * Les glitchs de la marque à l'instant t (s) : null au repos (la marque telle que dessinée, ses traits en place) ;
     * pendant une bouffée, de nouvelles tranches à chaque pas (les traits sautent ailleurs, s'effacent un instant), puis
     * tout revient. La première bouffée part à t = 0, les suivantes toutes les GLITCH_EVERY s, un peu en retard.
     */
    public static List<Slice> glitchAt(Art art, double t) {
        if (t < 0) return null;
        long burst = (long) Math.floor(t / GLITCH_EVERY);
        Random r = new Random(burst * 7919L + 17);
        double start = burst * GLITCH_EVERY + (burst == 0 ? 0 : .3 * GLITCH_EVERY * r.nextDouble());
        int steps = 3 + r.nextInt(3);
        int blank = 1 + r.nextInt(steps);              // un pas sans tranche : les traits disparaissent un instant
        int count = 1 + r.nextInt(3);
        if (t < start) return null;
        int step = (int) Math.floor((t - start) / GLITCH_STEP);
        if (step >= steps) return null;
        if (step == blank) return List.of();
        return slices(art, burst * 131L + step, count, 1, -1);
    }

    /**
     * Des tranches tirées au hasard (graine `seed` : le même tirage pour la même graine) : `count` tranches de 14 à 58
     * unités de haut, chacune une frange qui glisse de 30 à 80 unités vers son côté ou une déchirure de 20 à 60 unités ;
     * les écarts multipliés par `amp`, sur la case `slot` (−1 : toute la ligne).
     */
    public static List<Slice> slices(Art art, long seed, int count, double amp, int slot) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 1);
        List<Slice> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double u = r.nextDouble();
            Role role = u < .4 ? Role.CYAN : u < .8 ? Role.VIOLET : Role.INK;
            double h = 14 + 44 * r.nextDouble();
            double y0 = art.minY() + (art.height() - h) * r.nextDouble();
            double size = amp * (role == Role.INK ? 20 + 40 * r.nextDouble() : 30 + 50 * r.nextDouble());
            double dx = role == Role.CYAN ? -size : role == Role.VIOLET ? size : r.nextBoolean() ? size : -size;
            out.add(new Slice(role, y0, y0 + h, dx, slot));
        }
        return out;
    }

    /**
     * Dessine l'œuvre dans le repère courant (unités de la police). Sans glitch (null), telle que son SVG la dessine :
     * le G_ avec ses traits. Avec des tranches (une liste vide : la marque nette, sans traits), les franges sont refaites
     * à partir du blanc et du curseur, et chaque tranche glisse. `cursor` non nul : le curseur de cette couleur, sans
     * franges (un démarrage qui échoue).
     */
    public static void draw(GraphicsContext g, Art art, Look look, List<Slice> glitch, Color cursor) {
        int cursorSlot = art.slots() - 1;
        if (glitch == null) {
            for (Layer layer : art.layers()) {
                for (Part part : layer.parts()) {
                    Color color = colorOf(layer.role(), layer.color(), part.slot() == cursorSlot, cursor);
                    if (color == null) continue;
                    double dx = look.dx(part.slot()) + Math.signum(art.fringeDx(layer.role())) * look.spread(part.slot());
                    g.save();
                    g.translate(dx, 0);
                    fill(g, part, color, look.alpha(part.slot()));
                    g.restore();
                }
            }
            return;
        }
        double[] cuts = cuts(art, glitch);
        List<Part> shapes = new ArrayList<>(art.layer(Role.INK).parts());
        shapes.addAll(art.layer(Role.CURSOR).parts());
        for (Layer layer : art.layers()) {
            Role role = layer.role();
            List<Part> parts = role == Role.CYAN || role == Role.VIOLET ? shapes : layer.parts();
            for (Part part : parts) {
                Color color = colorOf(role, layer.color(), part.slot() == cursorSlot, cursor);
                double alpha = look.alpha(part.slot());
                if (color == null || alpha <= 0) continue;
                double dx = look.dx(part.slot()) + art.fringeDx(role)
                        + Math.signum(art.fringeDx(role)) * look.spread(part.slot());
                drawSliced(g, part, role, color, alpha, dx, glitch, cuts);
            }
        }
    }

    /** La couleur d'un calque, ou null s'il ne se dessine pas (les franges d'un curseur recoloré). */
    private static Color colorOf(Role role, Color color, boolean ofCursor, Color cursor) {
        if (cursor == null || !ofCursor) return color;
        return role == Role.CURSOR ? cursor : null;
    }

    /** Les bords des tranches, du haut en bas, entre deux bornes bien au-delà de l'œuvre. */
    private static double[] cuts(Art art, List<Slice> glitch) {
        double[] ys = new double[2 + 2 * glitch.size()];
        ys[0] = art.minY() - 1000;
        ys[1] = art.minY() + art.height() + 1000;
        for (int k = 0; k < glitch.size(); k++) {
            ys[2 + 2 * k] = glitch.get(k).y0();
            ys[3 + 2 * k] = glitch.get(k).y1();
        }
        return java.util.Arrays.stream(ys).sorted().distinct().toArray();
    }

    /** Une partie, bande par bande : chaque bande glisse de la somme des tranches qui la couvrent. */
    private static void drawSliced(GraphicsContext g, Part part, Role role, Color color, double alpha, double dx,
                                   List<Slice> glitch, double[] cuts) {
        int bands = cuts.length - 1;
        double[] shift = new double[bands];
        boolean sliced = false;
        for (int b = 0; b < bands; b++) {
            double mid = (cuts[b] + cuts[b + 1]) / 2;
            for (Slice slice : glitch) {
                if (mid < slice.y0() || mid > slice.y1()) continue;
                if (slice.slot() >= 0 && slice.slot() != part.slot()) continue;
                if (slice.role() == Role.INK || slice.role() == role) shift[b] += slice.dx();
            }
            sliced |= shift[b] != 0;
        }
        if (!sliced) {
            g.save();
            g.translate(dx, 0);
            fill(g, part, color, alpha);
            g.restore();
            return;
        }
        // Les bandes voisines qui glissent d'autant sont dessinées d'un seul tenant : pas de couture entre elles.
        for (int b = 0, e; b < bands; b = e + 1) {
            e = b;
            while (e + 1 < bands && shift[e + 1] == shift[b]) e++;
            double y0 = cuts[b], y1 = cuts[e + 1];
            if (y1 <= part.minY() || y0 >= part.maxY()) continue;
            double x = dx + shift[b];
            g.save();
            g.beginPath();
            g.rect(part.minX() + x - 10, y0, part.maxX() - part.minX() + 20, y1 - y0);
            g.clip();
            g.translate(x, 0);
            fill(g, part, color, alpha);
            g.restore();
        }
    }

    // ================================================================== lecture des SVG

    static Art load(String resource) {
        String svg;
        try (InputStream in = Brand.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Marque Genesis absente : " + resource);
            svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Marque Genesis illisible : " + resource, error);
        }
        return parse(svg);
    }

    /** Les calques d'un SVG de la marque (M, L et Z absolus), dans l'ordre du dessin. */
    static Art parse(String svg) {
        record Raw(Role role, Color color, List<double[]> boxes, List<String> paths) { }
        List<Raw> raws = new ArrayList<>();
        Matcher path = PATH.matcher(svg);
        while (path.find()) {
            String d = null, fill = "#ffffff", type = "";
            Matcher a = ATTRIBUTE.matcher(path.group(1));
            while (a.find()) {
                switch (a.group(1)) {
                    case "d" -> d = a.group(2);
                    case "fill" -> fill = a.group(2);
                    case "class" -> type = a.group(2);
                    default -> { }
                }
            }
            if (d == null) continue;
            Color color = Color.web(fill);
            Role role = type.contains("cur") || color.equals(CURSOR) ? Role.CURSOR
                    : color.equals(CYAN) ? Role.CYAN : color.equals(VIOLET) ? Role.VIOLET : Role.INK;
            List<double[]> boxes = new ArrayList<>();
            List<String> paths = new ArrayList<>();
            for (String sub : d.split("(?=M)")) {
                if (sub.isBlank()) continue;
                Matcher n = NUMBER.matcher(sub);
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                boolean x = true;
                while (n.find()) {
                    double v = Double.parseDouble(n.group());
                    if (x) { minX = Math.min(minX, v); maxX = Math.max(maxX, v); }
                    else { minY = Math.min(minY, v); maxY = Math.max(maxY, v); }
                    x = !x;
                }
                boxes.add(new double[]{minX, maxX, minY, maxY});
                paths.add(sub.trim());
            }
            raws.add(new Raw(role, color, boxes, paths));
        }
        // Les cases : les lettres blanches et le curseur, de gauche à droite ; chaque partie va à la case la plus proche.
        List<Double> centers = new ArrayList<>();
        for (Raw raw : raws) {
            if (raw.role() == Role.INK || raw.role() == Role.CURSOR) {
                for (double[] b : raw.boxes()) centers.add((b[0] + b[1]) / 2);
            }
        }
        centers.sort(Comparator.naturalOrder());
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        List<Layer> layers = new ArrayList<>();
        for (Raw raw : raws) {
            List<Part> parts = new ArrayList<>();
            for (int k = 0; k < raw.paths().size(); k++) {
                double[] b = raw.boxes().get(k);
                double center = (b[0] + b[1]) / 2;
                int slot = 0;
                for (int c = 1; c < centers.size(); c++) {
                    if (Math.abs(centers.get(c) - center) < Math.abs(centers.get(slot) - center)) slot = c;
                }
                parts.add(new Part(raw.paths().get(k), b[0], b[1], b[2], b[3], slot));
                minX = Math.min(minX, b[0]);
                maxX = Math.max(maxX, b[1]);
                minY = Math.min(minY, b[2]);
                maxY = Math.max(maxY, b[3]);
            }
            parts.sort(Comparator.comparingDouble(Part::minX));
            layers.add(new Layer(raw.role(), raw.color(), List.copyOf(parts)));
        }
        return new Art(List.copyOf(layers), minX, minY, maxX - minX, maxY - minY, centers.size(),
                fringeOffset(layers, Role.CYAN), fringeOffset(layers, Role.VIOLET));
    }

    /**
     * L'écart d'une frange au blanc : pour chaque case, l'écart des bords gauches et celui des bords droits. Un trait ne
     * repousse qu'un bord (sa tranche dépasse d'un côté) : le plus petit des écarts est celui de la frange.
     */
    private static double fringeOffset(List<Layer> layers, Role fringe) {
        double best = Double.NaN;
        for (Layer layer : layers) {
            if (layer.role() != fringe) continue;
            for (Part part : layer.parts()) {
                for (Layer shape : layers) {
                    if (shape.role() != Role.INK && shape.role() != Role.CURSOR) continue;
                    for (Part ink : shape.parts()) {
                        if (ink.slot() != part.slot()) continue;
                        double left = part.minX() - ink.minX(), right = part.maxX() - ink.maxX();
                        double d = Math.abs(left) < Math.abs(right) ? left : right;
                        if (Double.isNaN(best) || Math.abs(d) < Math.abs(best)) best = d;
                    }
                }
            }
        }
        return Double.isNaN(best) ? 0 : best;
    }
}
