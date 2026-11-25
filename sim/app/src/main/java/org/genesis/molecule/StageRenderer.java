package org.genesis.molecule;

import org.genesis.app.I18n;
import org.genesis.common.Plan;

import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.Blend;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.ColorInput;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La vue 2.5D du chemin d'une étape du schéma (SchemeStage) : un replay, dessiné sur un canevas, dans le fond même de la
 * scène du laboratoire (son bleu nuit, rien d'autre : pas de décor qui ne mesure rien).
 *
 * <p>Les atomes sont des sphères de verre calculées pixel par pixel : la teinte de leur élément, la lumière principale
 * venue d'en haut à gauche (un reflet net), un liseré de Fresnel sur le bord, la lumière que le verre concentre de
 * l'autre côté, et un fin contour sombre qui les détache de ce qui est derrière. Les liaisons sont des tubes de verre
 * clair, éclairés de même selon leur angle à l'écran. Tout est vu en perspective et dessiné du fond vers l'avant : chaque
 * atome couvre ce qui est derrière lui, et ce qui est loin se voile vers le fond.
 *
 * <p>Tout ce qui porte une grandeur vient du calcul. La géométrie montrée est celle que le moteur a posée et calculée à
 * cette position du chemin (GFN1-xTB, entre deux images aussi) ; le nombre de tubes d'une liaison, son indice de Mayer à
 * cette géométrie (MoleculeScene.strokes). Une liaison qui se forme (vert) ou se rompt (orange) est un filament de verre
 * lumineux : son épaisseur, son opacité et son éclat suivent son indice de Mayer, d'un fil à peine visible (indice nul)
 * au tube plein (0,75) ; sous 0,02, un trait fin relie la paire. Les lueurs vives sont donc calculées : l'éclat des
 * liaisons qui changent, et, en mode charges, la couleur et le halo de chaque atome (sa charge de Mulliken). Le verre, ses
 * reflets et la lueur douce qu'il diffuse autour de chaque bille ne sont qu'un décor — la légende le dit (« verre et
 * reflets »). Les symboles s'écrivent comme dans une formule squelettique : les carbones et
 * les hydrogènes sous-entendus, les autres éléments écrits, les atomes qui réagissent numérotés.
 *
 * <p>La vue est calculée : elle regarde le long de l'axe le plus mince des atomes qui réagissent (moments de leurs
 * positions sur tout le chemin imposé), si bien que les liaisons qui changent sont dans le plan de l'écran, puis
 * s'incline un peu. Les flèches courbes sont celles du dessin, posées sur les atomes calculés.
 */
final class StageRenderer {
    /** Le chemin d'une étape, tel que le moteur l'a calculé (MoleculeSchemeStep). */
    static final class Path {
        final int[] z;
        final double[][][] frames;      // [image][atome] = {x, y, z} (Å, chaque image centrée sur ses masses)
        final double[][] charges;       // [image][atome] (e, Mulliken)
        final double[] energies;        // eV, depuis la première image
        final int scanStart, scanEnd;   // les images du chemin imposé : [scanStart, scanEnd)
        final int[][] changes;          // {a, b, ordre avant, ordre après}
        final double[][] orders;        // [image][changement] : indice de Mayer
        final int[][] curly;            // les flèches courbes du dessin : {sa, sb, ta, tb} (−1 : aucun)
        final Set<Integer> jumps;       // k : le chemin saute de la branche à l'autre entre k et k + 1
        final boolean[] reacting;       // les atomes d'une liaison qui change
        final int[][] bondPairs;        // les paires liées dans au moins une image : {a, b}
        final double[][] bondOrders;    // [image][paire] : indice de Mayer
        final double[] radii;           // Å : le rayon covalent de chaque atome
        /** Le numéro montré de chaque atome, gardé d'une étape à l'autre du schéma (C3 reste C3) ; null : son rang + 1. */
        int[] ids;
        /**
         * [image][atome] : la présence de chaque atome, de 0 (absent) à 1 — le relais entre deux étapes, où le
         * sous-produit de l'une s'en va et le réactif écrit de la suivante arrive ; null : tous présents.
         */
        double[][] alpha;
        /** Le relais entre deux étapes : aucune géométrie n'est calculée entre ses deux images. */
        boolean relay;
        /** Le chemin dont la vue calculée sert (le relais regarde déjà l'étape qui suit) ; null : celui-ci. */
        Path viewOf;
        /** Ce que la vue dit pendant le relais (« étape 1 → 2 · … »). */
        String relayNote;

        Path(int[] z, double[][][] frames, double[][] charges, double[] energies, int scanStart, int scanEnd,
             int[][] changes, double[][] orders, int[][] curly, Set<Integer> jumps) {
            this(z, frames, charges, energies, scanStart, scanEnd, changes, orders, curly, jumps, new int[0][], new double[0][],
                    new double[0]);
        }

        Path(int[] z, double[][][] frames, double[][] charges, double[] energies, int scanStart, int scanEnd,
             int[][] changes, double[][] orders, int[][] curly, Set<Integer> jumps, int[][] bondPairs, double[][] bondOrders,
             double[] radii) {
            this.bondPairs = bondPairs;
            this.bondOrders = bondOrders;
            this.radii = radii;
            this.z = z;
            this.frames = frames;
            this.charges = charges;
            this.energies = energies;
            this.scanStart = scanStart;
            this.scanEnd = scanEnd;
            this.changes = changes;
            this.orders = orders;
            this.curly = curly;
            this.jumps = jumps;
            reacting = new boolean[z.length];
            for (int[] c : changes) {
                if (c[0] < z.length) reacting[c[0]] = true;
                if (c[1] < z.length) reacting[c[1]] = true;
            }
        }

        int count() {
            return frames.length;
        }

        /** Le numéro montré de l'atome i : celui de tout le schéma. */
        int number(int i) {
            return ids != null && i >= 0 && i < ids.length && ids[i] > 0 ? ids[i] : i + 1;
        }

        /** Le nom d'un atome, tel qu'il est écrit partout : « C3 », « O11 ». */
        String name(int i) {
            return MoleculeView.symbolOf(z[i]) + number(i);
        }

        /** Le changement de la paire (a, b), ou −1. */
        int change(int a, int b) {
            for (int k = 0; k < changes.length; k++) {
                if (changes[k][0] == a && changes[k][1] == b || changes[k][0] == b && changes[k][1] == a) return k;
            }
            return -1;
        }

        /** Le genre d'un changement : 1 se forme, −1 se rompt, 0 son ordre change. */
        static int kind(int[] c) {
            return c[2] == 0 ? 1 : c[3] == 0 ? -1 : 0;
        }
    }

    /** La géométrie montrée : celle que le moteur a calculée à la position `s` du chemin (images, fractionnaire). */
    static final class Pose {
        final double[][] p;
        final double[] q, radius;
        final List<MoleculeScene.Bond> bonds;
        final double s;
        /** La présence de chaque atome (0 → 1) ; null : tous présents. */
        final double[] alpha;

        Pose(double[][] p, double[] q, double[] radius, List<MoleculeScene.Bond> bonds, double s) {
            this(p, q, radius, bonds, s, null);
        }

        Pose(double[][] p, double[] q, double[] radius, List<MoleculeScene.Bond> bonds, double s, double[] alpha) {
            this.p = p;
            this.q = q;
            this.radius = radius;
            this.bonds = bonds;
            this.s = s;
            this.alpha = alpha;
        }
    }

    // ------------------------------------------------------------------ l'apparence
    static final Color FORMING = MoleculeScene.FORMING, BREAKING = MoleculeScene.BREAKING, CURLY = Plan.MARK;
    static final Color CHANGED = Color.web("#9DB7FF");
    private static final Color INK_DARK = Color.web("#040A14");
    private static final Color CARD = Color.web("#08111E", .86), CARD_EDGE = Color.web("#FFFFFF", .07);
    /** Le fond de la scène du laboratoire (MoleculeScene), au centre, à mi-chemin, au bord. */
    private static final Color SKY_CENTRE = Color.web("#183552"), SKY_MIDDLE = Color.web("#0E2135"), SKY_EDGE = Color.web("#050B14");
    /** Le voile de la profondeur : vers le ton moyen du fond. */
    private static final Color VEIL = Color.web("#0C1C2E");
    /** Le contour sombre d'une sphère (il la détache de ce qui est derrière). */
    private static final Color OUTLINE = Color.web("#02060C");
    /** Le verre clair des liaisons. */
    private static final Color GLASS = Color.web("#D7E5F2");
    /** Le rayon d'un tube (Å) : une liaison simple, ceux d'une liaison multiple, leur écart. */
    static final double STICK = .08, THIN = .054, GAP = .16;
    /** À partir de cet indice de Mayer, une liaison qui se forme ou se rompt est un tube plein. */
    static final double SOLID = .75;
    /** Sous cet indice, plus de filament : un trait fin relie la paire. */
    static final double PAIR = .02;
    /** Au-delà (Å d'un coup), la géométrie montrée saute au lieu de glisser (un saut de branche, une autre image). */
    private static final double SNAP = .35;
    private static final double PITCH = .38, YAW = -.34, FOV = 28;
    /** La profondeur la plus voilée (fraction du chemin vers le fond) et le nombre de voiles gardés. */
    private static final double FOG = .42;
    private static final int FOG_LEVELS = 6;

    /** Le rayon montré d'un atome : 0,14 Å + 0,32 × son rayon covalent (H 0,24 Å, C 0,38 Å, Si 0,51 Å, I 0,57 Å). */
    static double ballRadius(double covalent) {
        return .14 + .32 * covalent;
    }

    /**
     * L'éclat d'une liaison qui se forme ou se rompt, de son indice de Mayer m : 0 sous PAIR, 1 dès SOLID, et entre, la
     * part du chemin vers le tube plein (m / SOLID).
     */
    static double strength(double m) {
        return m < PAIR ? 0 : Math.min(1, m / SOLID);
    }

    // ------------------------------------------------------------------ les couches
    private final Pane root = new Pane();
    /** L'image de l'œil gauche (la seule hors stéréo), celle de l'œil droit, la légende ; au-dessus, la souris. */
    private final Canvas canvas = new Canvas(), second = new Canvas(), legendLayer = new Canvas();
    private final Pane input = new Pane();

    // ------------------------------------------------------------------ la stéréo
    /**
     * La vue en relief : anaglyphe (lunettes rouge-cyan : l'œil gauche, en gris, dans le rouge ; l'œil droit, en couleur,
     * dans le vert et le bleu — un anaglyphe « demi-couleur », qui évite qu'un atome rouge ne paraisse qu'à un œil) ou
     * côte à côte (l'œil gauche à gauche : un visiocasque, un écran 3D, ou la vision parallèle). Les deux yeux regardent
     * la cible, écartés de ±EYE autour d'elle.
     */
    enum Stereo { OFF, ANAGLYPH, SIDE }

    private Stereo stereo = Stereo.OFF;
    static final double EYE = Math.toRadians(1.5);
    private final ColorInput red = new ColorInput(0, 0, 10, 10, Color.RED), cyan = new ColorInput(0, 0, 10, 10, Color.CYAN);
    private double eyeWidth = 10;
    private final Text probe = new Text();

    // ------------------------------------------------------------------ l'état
    private Path path;
    private Pose pose;
    private double[][] shown = new double[0][];
    /** La base calculée : e1 (à droite), e2 (en haut), e3 (vers l'œil). */
    private double[] e1 = {1, 0, 0}, e2 = {0, 1, 0}, e3 = {0, 0, 1};
    private double reach = 4;
    double yaw = YAW, pitch = PITCH, distance = 30;
    private double[] target = new double[3];
    // La caméra de cette image : l'œil, ses axes (monde), la focale (px), la taille.
    private double[] eye = {0, 0, 30}, right = {1, 0, 0}, up = {0, 1, 0}, toward = {0, 0, 1};
    private double focal = 600, width = 10, height = 10, frameW, frameH;
    private boolean framed, handled, dirty = true;
    boolean allLabels, charges, curly = true, trails, hydrogens = true, legendFolded;
    int hovered = -1, hoveredChange = -1;
    double mouseX, mouseY;
    private double legendX, legendY, legendW, legendH, legendTitleH;
    private String legendKey = "";
    /** Par atome, à l'écran : x, y, px par Å à sa profondeur, profondeur (Å devant l'œil). */
    private double[][] screen = new double[0][];
    private boolean[] drawn = new boolean[0];
    private double nearest, farthest;
    /** L'échelle de l'écran (px de l'écran par px logique) : les sphères sont calculées à cette finesse. */
    private double pixels = 1;

    StageRenderer() {
        legendLayer.setMouseTransparent(true);
        canvas.setMouseTransparent(true);
        second.setMouseTransparent(true);
        second.setVisible(false);
        root.getChildren().addAll(canvas, second, legendLayer, input);
        legendLayer.widthProperty().bind(root.widthProperty());
        legendLayer.heightProperty().bind(root.heightProperty());
        input.prefWidthProperty().bind(root.widthProperty());
        input.prefHeightProperty().bind(root.heightProperty());
        input.setPickOnBounds(true);
        root.widthProperty().addListener((o, a, b) -> dirty = true);
        root.heightProperty().addListener((o, a, b) -> dirty = true);
        root.setMinSize(100, 100);
        root.setStyle("-fx-background-color: #050B14;");
    }

    Pane getNode() {
        return root;
    }

    /** Le calque qui reçoit la souris (au-dessus des images des deux yeux). */
    Pane input() {
        return input;
    }

    Stereo stereo() {
        return stereo;
    }

    /** Le relief : aucun, anaglyphe rouge-cyan, ou côte à côte. */
    void stereo(Stereo mode) {
        stereo = mode;
        canvas.setEffect(null);
        second.setEffect(null);
        second.setBlendMode(null);
        second.setVisible(mode != Stereo.OFF);
        if (mode == Stereo.ANAGLYPH) {
            canvas.setEffect(new Blend(BlendMode.MULTIPLY, new ColorAdjust(0, -1, 0, 0), red));
            second.setEffect(new Blend(BlendMode.MULTIPLY, null, cyan));
            second.setBlendMode(BlendMode.ADD);
        }
        framed = false;
        dirty = true;
    }

    /** Un x de la souris dans l'image d'un œil (côte à côte : la moitié droite répète la gauche). */
    private double eyeX(double x) {
        return stereo == Stereo.SIDE && x >= eyeWidth ? x - eyeWidth : x;
    }

    // ================================================================== les données
    void setPath(Path p) {
        setPath(p, false);
    }

    /**
     * Le chemin à montrer. `continuous` (l'étape suivante du même schéma, ses images alignées sur celles de la
     * précédente) : la vue ne repart pas de zéro. Tournée à la main, elle reste telle quelle ; sinon elle glisse en
     * moins d'une seconde vers la vue calculée du nouveau chemin — la molécule ne saute pas d'une étape à l'autre.
     */
    void setPath(Path p, boolean continuous) {
        boolean keep = continuous && path != null && p != null && framed && width > 2 && height > 2;
        path = p;
        if (keep && handled) {
            dirty = true;                          // la vue de l'utilisateur, gardée telle quelle
            return;
        }
        if (!keep) {
            shown = new double[0][];
            orient();
            framed = false;
            handled = false;
            yaw = YAW;
            pitch = PITCH;
            ease = 1;
            dirty = true;
            return;
        }
        // D'où l'on part : la vue de maintenant ; où l'on va : la vue calculée du nouveau chemin, cadrée.
        double[][] from = {e1.clone(), e2.clone(), e3.clone()};
        double[] fromTarget = target.clone();
        double fromDistance = distance, fromYaw = yaw, fromPitch = pitch;
        orient();
        yaw = YAW;
        pitch = PITCH;
        frame();
        double[][] to = {e1.clone(), e2.clone(), e3.clone()};
        double[][] s = new double[3][3];
        for (int k = 0; k < 3; k++) for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) s[r][c] += from[k][r] * to[k][c];
        easeTurn = PathMotion.horn(s);
        easeFrom = from;
        easeTarget = new double[][]{fromTarget, target.clone()};
        easeDistance = new double[]{fromDistance, distance};
        easeAngles = new double[]{fromYaw, fromPitch};
        ease = 0;
        e1 = from[0];
        e2 = from[1];
        e3 = from[2];
        target = fromTarget.clone();
        distance = fromDistance;
        yaw = fromYaw;
        pitch = fromPitch;
        dirty = true;
    }

    /** Le glissé de la vue d'un chemin à l'autre : 0 → 1 (1 : fini). */
    private double ease = 1;
    private double[] easeTurn = {1, 0, 0, 0}, easeDistance = {30, 30}, easeAngles = {YAW, PITCH};
    private double[][] easeFrom = new double[3][], easeTarget = new double[2][];
    private static final double EASE_S = .9;

    /** Un pas du glissé de la vue (vrai s'il a bougé). */
    private boolean easeStep(double dt) {
        if (ease >= 1) return false;
        if (handled) {
            ease = 1;                              // la main reprend la vue : le glissé s'arrête là
            return false;
        }
        ease = Math.min(1, ease + dt / EASE_S);
        double t = ease * ease * (3 - 2 * ease);
        double[] turn = PathMotion.partial(easeTurn, t);
        e1 = unit(PathMotion.rotate(turn, easeFrom[0]));
        e2 = unit(PathMotion.rotate(turn, easeFrom[1]));
        e3 = unit(PathMotion.rotate(turn, easeFrom[2]));
        for (int c = 0; c < 3; c++) target[c] = easeTarget[0][c] + (easeTarget[1][c] - easeTarget[0][c]) * t;
        distance = easeDistance[0] * Math.pow(easeDistance[1] / easeDistance[0], t);
        yaw = easeAngles[0] + (YAW - easeAngles[0]) * t;
        pitch = easeAngles[1] + (PITCH - easeAngles[1]) * t;
        if (ease >= 1) {
            frameW = width;
            frameH = height;
            framed = true;
        }
        dirty = true;
        return true;
    }

    /** Le glissé de la vue est-il en cours ? (aperçus, tests) */
    boolean easing() {
        return ease < 1;
    }

    Path path() {
        return path;
    }

    Pose pose() {
        return pose;
    }

    void setPose(Pose p) {
        setPose(p, false);
    }

    /**
     * La géométrie à montrer. `follow` (la lecture, une géométrie par image de l'écran) : montrée telle quelle ; sinon
     * (une image de plus, un clic sur la frise), la vue y glisse en quelques centièmes de seconde, et y saute au-delà de
     * SNAP.
     */
    void setPose(Pose p, boolean follow) {
        pose = p;
        if (p == null) {
            shown = new double[0][];
            dirty = true;
            return;
        }
        boolean snap = follow || shown.length != p.p.length;
        for (int i = 0; i < p.p.length && !snap; i++) snap = dist(shown[i], p.p[i]) > SNAP;
        if (snap) {
            shown = new double[p.p.length][];
            for (int i = 0; i < p.p.length; i++) shown[i] = p.p[i].clone();
        }
        dirty = true;
    }

    /** L'horloge : la géométrie montrée rejoint la dernière calculée (quelques centièmes de seconde). */
    boolean tick(double dt) {
        easeStep(dt);
        if (pose == null || shown.length != pose.p.length) return dirty;
        double k = 1 - Math.exp(-dt * 18);
        double moved = 0;
        for (int i = 0; i < shown.length; i++) {
            for (int c = 0; c < 3; c++) {
                double d = pose.p[i][c] - shown[i][c];
                shown[i][c] += d * k;
                moved = Math.max(moved, Math.abs(d));
            }
        }
        if (moved > 1e-4) dirty = true;
        return dirty;
    }

    void invalidate() {
        dirty = true;
    }

    // ================================================================== la vue calculée
    /**
     * La base de la vue : les axes propres des positions des atomes qui réagissent, sur toutes les images du chemin
     * imposé (tous les atomes, sans changement). L'œil regarde le long du plus mince (e3) ; le plus long va à droite ;
     * le gros des atomes est en bas, la réaction vers l'œil.
     */
    private void orient() {
        if (path == null || path.count() == 0) return;
        Path path = this.path.viewOf != null ? this.path.viewOf : this.path;
        int n = path.z.length;
        boolean core = path.scanEnd > path.scanStart;
        int from = core ? path.scanStart : 0, to = core ? path.scanEnd : path.count();
        List<double[]> points = new ArrayList<>();
        boolean any = false;
        for (boolean r : path.reacting) any |= r;
        for (int f = from; f < to; f++) {
            for (int i = 0; i < n; i++) if (!any || path.reacting[i]) points.add(path.frames[f][i]);
        }
        double[] c = new double[3];
        for (double[] p : points) for (int k = 0; k < 3; k++) c[k] += p[k] / points.size();
        double[][] m = new double[3][3];
        for (double[] p : points) {
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) m[a][b] += (p[a] - c[a]) * (p[b] - c[b]) / points.size();
        }
        double[][] axes = new double[3][];
        AtomMap.eigen(m, axes);
        double[] upAxis = axes[1], towardAxis = axes[0];
        double[] all = new double[3];
        for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) all[k] += path.frames[from][i][k] / n;
        double[] d = {all[0] - c[0], all[1] - c[1], all[2] - c[2]};
        if (dot(d, upAxis) > 0) upAxis = scale(upAxis, -1);
        if (dot(d, towardAxis) > 0) towardAxis = scale(towardAxis, -1);
        e2 = unit(upAxis);
        e3 = unit(towardAxis);
        e1 = unit(cross(e2, e3));
        reach = 1;
        for (double[][] frame : path.frames) for (int i = 0; i < n; i++) reach = Math.max(reach, norm(frame[i]));
    }

    /** La vue calculée, de nouveau (bouton, double-clic, R). */
    void reset() {
        yaw = YAW;
        pitch = PITCH;
        handled = false;
        framed = false;
        dirty = true;
    }

    void rotate(double dx, double dy) {
        yaw += dx * .008;
        pitch = Math.max(-1.45, Math.min(1.45, pitch + dy * .008));
        handled = true;
        dirty = true;
    }

    /** Glisser la vue : la cible suit la souris, à sa profondeur. */
    void pan(double dx, double dy) {
        double k = distance / focal;
        for (int c = 0; c < 3; c++) target[c] += -dx * k * right[c] + dy * k * up[c];
        handled = true;
        dirty = true;
    }

    /** Rapprocher l'œil, vers ce qui est sous la souris (à la profondeur de la cible). */
    void zoomAt(double x, double y, double delta) {
        x = eyeX(x);
        double next = Math.max(1.5 + .3 * reach, Math.min(40 * reach + 20, distance * Math.exp(-delta / 400)));
        double sx = (x - width / 2) / focal, sy = (y - height / 2) / focal;
        double[] under = new double[3];
        for (int c = 0; c < 3; c++) under[c] = target[c] + (sx * right[c] - sy * up[c]) * distance;
        for (int c = 0; c < 3; c++) target[c] = under[c] - (sx * right[c] - sy * up[c]) * next;
        distance = next;
        handled = true;
        dirty = true;
    }

    boolean handled() {
        return handled;
    }

    /** La base de la vue calculée : {droite, haut, vers l'œil} (tests). */
    double[][] basis() {
        return new double[][]{e1.clone(), e2.clone(), e3.clone()};
    }

    /** Où un atome a été dessiné : {x, y, px par Å, profondeur} (aperçus et tests ; null avant). */
    double[] screenOf(int atom) {
        return atom >= 0 && atom < screen.length ? screen[atom].clone() : null;
    }

    /** px par Å à la profondeur de la cible (l'échelle de la vue). */
    double zoom() {
        return focal / Math.max(.1, distance);
    }

    // ================================================================== la caméra
    /** L'œil et ses axes : la base calculée tournée de `yaw` (autour du haut) puis de `pitch` (autour de la droite). */
    private void place() {
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        right = inWorld(cy, 0, sy);
        up = inWorld(sp * sy, cp, -sp * cy);
        toward = inWorld(-cp * sy, sp, cp * cy);
        for (int c = 0; c < 3; c++) eye[c] = target[c] + toward[c] * distance;
    }

    private double[] inWorld(double x, double y, double z) {
        return new double[]{x * e1[0] + y * e2[0] + z * e3[0], x * e1[1] + y * e2[1] + z * e3[1], x * e1[2] + y * e2[2] + z * e3[2]};
    }

    /** Un point (Å) à l'écran : {x, y, px par Å à cette profondeur, profondeur devant l'œil (Å)}. */
    private double[] project(double[] p, double[] out) {
        double dx = p[0] - eye[0], dy = p[1] - eye[1], dz = p[2] - eye[2];
        double xc = dx * right[0] + dy * right[1] + dz * right[2];
        double yc = dx * up[0] + dy * up[1] + dz * up[2];
        double zc = -(dx * toward[0] + dy * toward[1] + dz * toward[2]);
        double k = focal / Math.max(.05, zc);
        out[0] = width / 2 + xc * k;
        out[1] = height / 2 - yc * k;
        out[2] = k;
        out[3] = zc;
        return out;
    }

    /**
     * Cadre le chemin imposé (ses images) dans la place libre de l'écran : la cible au centre de ses atomes, l'œil assez
     * loin pour que leur sphère englobante tienne, décalée à droite de la légende.
     */
    private void frame() {
        if (path == null || path.count() == 0) return;
        Path path = this.path.viewOf != null ? this.path.viewOf : this.path;
        boolean core = path.scanEnd > path.scanStart + 1;
        int from = core ? path.scanStart : 0, to = core ? path.scanEnd : path.count();
        double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
        for (int f = from; f < to; f++) {
            for (double[] p : path.frames[f]) {
                for (int c = 0; c < 3; c++) {
                    lo[c] = Math.min(lo[c], p[c]);
                    hi[c] = Math.max(hi[c], p[c]);
                }
            }
        }
        double[] centre = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        double radius = .6;
        for (int f = from; f < to; f++) for (double[] p : path.frames[f]) radius = Math.max(radius, dist(p, centre) + .45);
        double left = legendLeft(), top = 64, bottom = 34, rightMargin = 28;
        double roomW = Math.max(120, width - left - rightMargin), roomH = Math.max(120, height - top - bottom);
        double halfV = Math.atan(Math.tan(Math.toRadians(FOV / 2)) * roomH / height);
        double halfH = Math.atan(Math.tan(Math.toRadians(FOV / 2)) * roomW / height);
        distance = Math.max(2, radius / Math.sin(Math.min(halfV, halfH)));
        target = centre.clone();
        double[] s = new double[4];
        for (int pass = 0; pass < 4; pass++) {
            place();
            double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
            for (int f = from; f < to; f++) {
                double[][] frame = path.frames[f];
                for (int i = 0; i < frame.length; i++) {
                    project(frame[i], s);
                    double r = .45 * s[2];
                    x0 = Math.min(x0, s[0] - r);
                    x1 = Math.max(x1, s[0] + r);
                    y0 = Math.min(y0, s[1] - r);
                    y1 = Math.max(y1, s[1] + r);
                }
            }
            // Le centre de la place libre, et non de l'écran ; l'œil s'éloigne ou s'approche pour la remplir.
            double k = distance / focal;
            double dx = (x0 + x1) / 2 - (left + roomW / 2), dy = (y0 + y1) / 2 - (top + roomH / 2);
            for (int c = 0; c < 3; c++) target[c] += dx * k * right[c] - dy * k * up[c];
            double fill = Math.max((x1 - x0) / roomW, (y1 - y0) / roomH);
            distance = Math.max(1.5 + .3 * reach, distance * Math.max(.5, Math.min(2, fill)));
        }
        place();
        frameW = width;
        frameH = height;
        framed = true;
    }

    private double legendLeft() {
        return legendFolded || width < 820 ? 24 : LEGEND_W + 40;
    }

    private double radius(int atom) {
        double r = pose != null && atom < pose.radius.length && pose.radius[atom] > 0 ? pose.radius[atom] : .75;
        return ballRadius(r);
    }

    // ================================================================== le dessin
    /** Dessine l'image si quelque chose a changé (la géométrie, la vue, la souris, la taille). */
    private double rootW, rootH;

    void render() {
        double w = root.getWidth(), h = root.getHeight();
        if (w < 2 || h < 2) return;
        if (!dirty && w == rootW && h == rootH) return;
        dirty = false;
        rootW = w;
        rootH = h;
        // Chaque œil a son image : toute la vue (anaglyphe : les deux l'une sur l'autre), ou une moitié (côte à côte).
        double ew = stereo == Stereo.SIDE ? Math.floor(w / 2) : w;
        eyeWidth = ew;
        canvas.setWidth(ew);
        canvas.setHeight(h);
        canvas.relocate(0, 0);
        second.setWidth(stereo == Stereo.SIDE ? w - ew : w);
        second.setHeight(h);
        second.relocate(stereo == Stereo.SIDE ? ew : 0, 0);
        red.setWidth(w);
        red.setHeight(h);
        cyan.setWidth(w);
        cyan.setHeight(h);
        width = ew;
        height = h;
        focal = h / 2 / Math.tan(Math.toRadians(FOV / 2));
        pixels = outputScale();
        if (path != null && !handled && (!framed || Math.abs(ew - frameW) > .5 || Math.abs(h - frameH) > .5)) frame();
        boolean ready = path != null && pose != null && shown.length > 0;
        if (stereo == Stereo.OFF) {
            place();
            paintEye(canvas.getGraphicsContext2D(), ready);
        } else {
            // Les deux yeux de part et d'autre de la cible, puis la vue du milieu pour la souris (survol, clic).
            double straight = yaw;
            yaw = straight - EYE;
            place();
            paintEye(canvas.getGraphicsContext2D(), ready);
            yaw = straight + EYE;
            place();
            paintEye(second.getGraphicsContext2D(), ready);
            yaw = straight;
            place();
            for (int i = 0; i < screen.length && i < shown.length; i++) project(shown[i], screen[i]);
        }
        paintLegend(ready);
    }

    /** L'image d'un œil (ou de la vue, hors stéréo), la caméra déjà posée. */
    private void paintEye(GraphicsContext g, boolean ready) {
        int n = ready ? shown.length : 0;
        if (screen.length != n) {
            screen = new double[n][4];
            drawn = new boolean[n];
        }
        nearest = 1e9;
        farthest = -1e9;
        for (int i = 0; i < n; i++) {
            project(shown[i], screen[i]);
            drawn[i] = (hydrogens || path.z[i] != 1 || path.reacting[i]) && presence(i) > .02;
            if (drawn[i]) {
                nearest = Math.min(nearest, screen[i][3]);
                farthest = Math.max(farthest, screen[i][3]);
            }
        }
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.setGlobalAlpha(1);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setLineDashes(null);
        g.setImageSmoothing(true);
        sky(g);
        if (!ready) {
            g.setFill(Plan.INK2);
            g.setFont(font(Plan.LIGHT, 15));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.BASELINE);
            g.fillText(I18n.text("Le moteur pose la première image du chemin…"), width / 2, height / 2);
        } else {
            light(g);
            if (trails) trails(g);
            molecule(g);
            if (curly) curly(g);
            labels(g);
            if (hovered >= 0 && hovered < shown.length && drawn[hovered]) {
                double[] s = screen[hovered];
                double r = radius(hovered) * s[2] + 4;
                g.setStroke(Color.web("#FFFFFF", .75));
                g.setLineWidth(1.2);
                g.strokeOval(s[0] - r, s[1] - r, 2 * r, 2 * r);
            }
            jumpNote(g);
            scaleBar(g);
            hover(g);
        }
    }

    /** L'échelle de l'écran de la fenêtre (1 hors d'une fenêtre : aperçus, tests). */
    private double outputScale() {
        Scene scene = root.getScene();
        Window window = scene == null ? null : scene.getWindow();
        double s = window == null ? 1 : Math.max(window.getRenderScaleX(), window.getRenderScaleY());
        return Double.isFinite(s) && s >= 1 ? Math.min(3, s) : 1;
    }

    /** Le fond : celui de la scène du laboratoire, tel quel. */
    private void sky(GraphicsContext g) {
        // En anaglyphe, le même fond en gris : son bleu, vu en gris par un œil et en couleur par l'autre, virait au violet.
        boolean grey = stereo == Stereo.ANAGLYPH;
        g.setFill(new RadialGradient(0, 0, width * .5, height * .42, Math.max(width, height) * .75, false, CycleMethod.NO_CYCLE,
                new Stop(0, grey ? Color.gray(.17) : SKY_CENTRE), new Stop(.45, grey ? Color.gray(.11) : SKY_MIDDLE),
                new Stop(1, grey ? Color.gray(.04) : SKY_EDGE)));
        g.fillRect(0, 0, width, height);
    }

    /** Le voile d'une profondeur (0 devant, FOG au plus loin), arrondi à l'un des FOG_LEVELS voiles gardés. */
    private int veil(double depth) {
        double span = farthest - nearest;
        if (span < 1e-6) return 0;
        double t = Math.max(0, Math.min(1, (depth - nearest) / span));
        return (int) Math.round(Math.pow(t, 1.15) * (FOG_LEVELS - 1));
    }

    // ------------------------------------------------------------------ les lueurs (calculées)
    /**
     * Derrière la molécule, les lueurs. Une seule est un décor, à peine visible : celle que le verre de chaque bille
     * diffuse autour d'elle. Les autres viennent du calcul : le long d'une liaison qui se forme ou se rompt, une lueur de
     * sa couleur, d'autant plus vive que son indice de Mayer monte ; en mode charges, autour de chaque atome, un halo de
     * la couleur de sa charge de Mulliken, d'autant plus large et vif qu'elle est forte. Sous PAIR, un trait fin relie la
     * paire (pas encore liée, ou plus).
     */
    private void light(GraphicsContext g) {
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setLineCap(StrokeLineCap.ROUND);
        // Autour de chaque sphère, la lumière que son verre diffuse : une lueur douce de sa teinte (un décor, comme les reflets).
        for (int i = 0; i < shown.length; i++) {
            if (!drawn[i] || charges) continue;
            double r = radius(i) * screen[i][2] * 2.1;
            if (r < 3) continue;
            Image bloom = bloom(tone(path.z[i]));
            g.setGlobalAlpha((path.z[i] == 1 ? .05 : .09) * (1 - .6 * veil(screen[i][3]) / (double) (FOG_LEVELS - 1)) * presence(i));
            g.drawImage(bloom, screen[i][0] - r, screen[i][1] - r, 2 * r, 2 * r);
        }
        g.setGlobalAlpha(1);
        if (charges) {
            for (int i = 0; i < shown.length; i++) {
                if (!drawn[i]) continue;
                double q = charge(i), t = Math.min(1, Math.abs(q) / .8);
                if (Math.abs(q) < .05) continue;
                double r = radius(i) * screen[i][2] * (2 + 1.4 * t);
                Color c = (q < 0 ? MoleculeScene.RICH : MoleculeScene.POOR).deriveColor(0, 1, 1, (.16 + .42 * t) * presence(i));
                g.setFill(new RadialGradient(0, 0, screen[i][0], screen[i][1], r, false, CycleMethod.NO_CYCLE,
                        new Stop(0, c), new Stop(.5, c.deriveColor(0, 1, 1, .4)), new Stop(1, c.deriveColor(0, 1, 1, 0))));
                g.fillOval(screen[i][0] - r, screen[i][1] - r, 2 * r, 2 * r);
            }
        }
        for (int k = 0; k < path.changes.length; k++) {
            int[] c = path.changes[k];
            int kind = Path.kind(c);
            if (kind == 0 || !visible(c[0]) || !visible(c[1])) continue;
            double s = strength(mayer(k));
            if (s <= 0) continue;
            double[] a = screen[c[0]], b = screen[c[1]];
            double px = (a[2] + b[2]) / 2, core = 2 * STICK * px;
            Image glow = glow(kind > 0 ? FORMING : BREAKING);
            beam(g, glow, a[0], a[1], b[0], b[1], core * 5 + 10, .12 + .3 * s);
            beam(g, glow, a[0], a[1], b[0], b[1], core * 1.8 + 3, .1 + .35 * s);
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        for (int k = 0; k < path.changes.length; k++) {
            int[] c = path.changes[k];
            int kind = Path.kind(c);
            if (kind == 0 || !visible(c[0]) || !visible(c[1]) || mayer(k) >= PAIR) continue;
            double[] a = screen[c[0]], b = screen[c[1]];
            g.setStroke((kind > 0 ? FORMING : BREAKING).deriveColor(0, 1, 1, .42));
            g.setLineWidth(1);
            g.setLineDashes(3, 4);
            g.strokeLine(a[0], a[1], b[0], b[1]);
            g.setLineDashes(null);
        }
    }

    /**
     * Une lueur le long d'un segment, de (x0, y0) à (x1, y1), large de `across` px, d'opacité `alpha` : une capsule
     * dont les bouts s'arrondissent et s'éteignent comme ses bords (l'image `glow` en trois tranches : un bout, le
     * milieu étiré, l'autre bout) — aucune limite nette.
     */
    private static void beam(GraphicsContext g, Image glow, double x0, double y0, double x1, double y1, double across, double alpha) {
        double dx = x1 - x0, dy = y1 - y0, length = Math.hypot(dx, dy);
        if (length < 1) return;
        double ux = dx / length, uy = dy / length, cap = across / 2, side = GLOW_CAP;
        g.setGlobalAlpha(Math.max(0, Math.min(1, alpha)));
        g.setTransform(ux, uy, -uy, ux, x0, y0);
        g.drawImage(glow, 0, 0, side, 2 * side, -cap, -cap, cap, across);
        g.drawImage(glow, side, 0, 2, 2 * side, 0, -cap, length, across);
        g.drawImage(glow, side + 2, 0, side, 2 * side, length, -cap, cap, across);
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.setGlobalAlpha(1);
    }

    private final Map<Integer, Image> glows = new HashMap<>(), blooms = new HashMap<>();

    /** Une lueur ronde (64 × 64 px) de la couleur donnée, l'opacité en cloche de Gauss du centre au bord. */
    private Image bloom(Color colour) {
        return blooms.computeIfAbsent(pack(colour), k -> {
            int n = 64;
            WritableImage image = new WritableImage(n, n);
            PixelWriter out = image.getPixelWriter();
            int rgb = (int) Math.round(colour.getRed() * 255) << 16 | (int) Math.round(colour.getGreen() * 255) << 8
                    | (int) Math.round(colour.getBlue() * 255);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = (x + .5) / n * 2 - 1, dy = (y + .5) / n * 2 - 1, r2 = dx * dx + dy * dy;
                    int a = (int) Math.round(255 * Math.exp(-r2 / (2 * .32 * .32)) * clamp((1 - Math.sqrt(r2)) * 6));
                    out.setArgb(x, y, a << 24 | rgb);
                }
            }
            return image;
        });
    }

    private Image glow(Color colour) {
        return glows.computeIfAbsent(pack(colour), k -> glowStrip(colour));
    }

    private boolean visible(int atom) {
        return atom >= 0 && atom < shown.length && drawn[atom];
    }

    /** La présence d'un atome (0 → 1) : moins de 1 pendant le relais entre deux étapes, où il arrive ou s'en va. */
    private double presence(int atom) {
        return pose == null || pose.alpha == null || atom < 0 || atom >= pose.alpha.length ? 1 : pose.alpha[atom];
    }

    private double charge(int atom) {
        return pose.q != null && atom < pose.q.length ? pose.q[atom] : 0;
    }

    // ------------------------------------------------------------------ la molécule
    /** Un morceau à dessiner, rangé par profondeur : un atome, ou un bout de tube (de `from` à `to`, à l'écran). */
    private static final class Piece {
        double depth;
        int atom = -1;
        double x0, y0, x1, y1, widthPx, alpha;
        Color colour;
        int veil;
    }

    private final List<Piece> pieces = new ArrayList<>();
    private int used;

    private Piece piece() {
        if (used == pieces.size()) pieces.add(new Piece());
        Piece p = pieces.get(used++);
        p.atom = -1;
        p.alpha = 1;
        return p;
    }

    /**
     * La molécule, du fond vers l'avant : chaque atome (sa sphère, puis son symbole), chaque bout de tube — les tubes
     * partent de la surface des sphères ; chacun est rangé à la profondeur de son milieu, si bien qu'un atome couvre la
     * liaison qui part derrière lui et qu'une liaison qui vient vers l'œil passe devant le sien.
     */
    private void molecule(GraphicsContext g) {
        used = 0;
        for (int i = 0; i < shown.length; i++) {
            if (!drawn[i]) continue;
            Piece p = piece();
            p.atom = i;
            p.depth = screen[i][3];
        }
        for (MoleculeScene.Bond bond : pose.bonds) {
            int a = bond.a(), b = bond.b();
            if (!visible(a) || !visible(b)) continue;
            if (path.change(a, b) >= 0) continue;                 // dessinée avec les changements
            double presence = MoleculeScene.fade(bond) * Math.min(presence(a), presence(b));
            if (presence < .03) continue;
            strands(a, b, bond.piAxis(), bond.order(), null, presence);
        }
        for (int k = 0; k < path.changes.length; k++) {
            int[] c = path.changes[k];
            int a = c[0], b = c[1];
            if (!visible(a) || !visible(b)) continue;
            double m = mayer(k);
            int kind = Path.kind(c);
            MoleculeScene.Bond bond = bond(a, b);
            double[] pi = bond == null ? null : bond.piAxis();
            if (kind == 0) {
                if (m >= .05) strands(a, b, pi, m, CHANGED, 1);
            } else if (m >= SOLID) {
                strands(a, b, pi, m, kind > 0 ? FORMING : BREAKING, 1);
            } else if (m >= PAIR) {
                // Le filament : de plus en plus épais, opaque et vif à mesure que l'indice monte vers SOLID.
                double s = strength(m);
                strand(a, b, new double[3], STICK * (.24 + .76 * Math.sqrt(s)), kind > 0 ? FORMING : BREAKING, .45 + .55 * s);
            }
        }
        Piece[] order = pieces.subList(0, used).toArray(Piece[]::new);
        Arrays.sort(order, (p, q) -> Double.compare(q.depth, p.depth));
        for (Piece p : order) {
            if (p.atom >= 0) {
                atom(g, p.atom);
                letter(g, p.atom);
            } else {
                tube(g, p);
            }
        }
        g.setGlobalAlpha(1);
        g.setTransform(1, 0, 0, 1, 0, 0);
    }

    /**
     * Une liaison : un à trois tubes selon son indice de Mayer m (MoleculeScene.strokes), côte à côte dans le plan
     * perpendiculaire à sa part π ; le second tube d'une liaison conjuguée, plus fin. `tint` teinte le verre (ce qui
     * change), `presence` l'efface (une liaison qui s'en va, MoleculeScene.fade).
     */
    private void strands(int a, int b, double[] piAxis, double m, Color tint, double presence) {
        double[] u = sub(shown[b], shown[a]);
        double length = norm(u);
        if (length < 1e-6) return;
        u = scale(u, 1 / length);
        double[] w = side(u, piAxis);
        int count = MoleculeScene.strokes(m);
        boolean partial = MoleculeScene.partial(m);
        for (int s = 0; s < count; s++) {
            double offset = count == 1 ? 0 : count == 2 ? (s - .5) * GAP : (s - 1) * GAP * .9;
            double r = count == 1 ? STICK : partial && s == count - 1 ? .036 : THIN;
            strand(a, b, scale(w, offset), r, tint, presence);
        }
    }

    /**
     * Un tube de a à b, décalé de `shift` (Å), de rayon r (Å) : deux moitiés, chacune de la surface de sa sphère au milieu,
     * de la teinte de son atome (fondue dans le verre clair) ou de `tint`.
     */
    private void strand(int a, int b, double[] shift, double r, Color tint, double alpha) {
        double[] A = add(shown[a], shift), B = add(shown[b], shift);
        double[] u = sub(B, A);
        double length = norm(u);
        if (length < 1e-6) return;
        u = scale(u, 1 / length);
        double ra = Math.sqrt(Math.max(0, sq(radius(a)) - r * r)), rb = Math.sqrt(Math.max(0, sq(radius(b)) - r * r));
        if (ra + rb >= length) return;                             // les sphères se touchent : rien entre elles
        double[] from = add(A, scale(u, ra)), to = sub(B, scale(u, rb)), middle = scale(add(A, B), .5);
        if (dot(sub(middle, from), u) <= 0) middle = scale(add(from, to), .5);
        half(from, middle, r, colourOf(a, tint), alpha);
        half(middle, to, r, colourOf(b, tint), alpha);
    }

    /** La teinte d'une moitié de tube : le verre clair teinté de son atome ; ce qui change, de la couleur de son genre
     * (la même que sa courbe image par image : vert, orange, bleu). */
    private Color colourOf(int atom, Color tint) {
        Color body = charges ? chargeColour(charge(atom)) : tone(path.z[atom]);
        Color glass = body.interpolate(GLASS, .58);
        return tint == null ? glass : glass.interpolate(tint, tint == CHANGED ? .82 : .62);
    }

    private final double[] s0 = new double[4], s1 = new double[4];

    private void half(double[] from, double[] to, double r, Color colour, double alpha) {
        project(from, s0);
        project(to, s1);
        double[] middle = scale(add(from, to), .5);
        double[] sm = project(middle, new double[4]);
        Piece p = piece();
        p.depth = sm[3];
        p.x0 = s0[0];
        p.y0 = s0[1];
        p.x1 = s1[0];
        p.y1 = s1[1];
        p.widthPx = 2 * r * sm[2];
        p.colour = colour;
        p.alpha = alpha;
        p.veil = veil(sm[3]);
    }

    /** Un bout de tube : la tranche de verre éclairée pour son angle, étirée de `from` à `to`. */
    private void tube(GraphicsContext g, Piece p) {
        double dx = p.x1 - p.x0, dy = p.y1 - p.y0, length = Math.hypot(dx, dy);
        if (length < .4 || p.widthPx < .3) return;
        double ux = dx / length, uy = dy / length;
        double angle = Math.atan2(uy, ux);
        Image strip = strip(p.colour, angle, p.veil);
        double across = p.widthPx / STRIP_CORE * strip.getHeight();
        // Un peu plus long qu'il ne faut : les deux moitiés se rejoignent sans fente.
        double over = Math.min(.8, length * .1);
        g.setGlobalAlpha(Math.max(0, Math.min(1, p.alpha)));
        g.setTransform(ux, uy, -uy, ux, p.x0, p.y0);
        g.drawImage(strip, -over, -across / 2, length + 2 * over, across);
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.setGlobalAlpha(1);
    }

    /** Un atome : sa sphère de verre, de la teinte de son élément (ou de sa charge), voilée par sa profondeur. */
    private void atom(GraphicsContext g, int i) {
        double r = radius(i) * screen[i][2];
        if (r < .8) return;
        Color body = charges ? chargeColour(charge(i)) : tone(path.z[i]);
        int veil = veil(screen[i][3]);
        int mip = mip(r * pixels);
        Image sprite = sphere(body, veil, mip);
        double size = sprite.getWidth() * r / MIPS[mip];
        g.setGlobalAlpha(presence(i));
        g.drawImage(sprite, screen[i][0] - size / 2, screen[i][1] - size / 2, size, size);
        g.setGlobalAlpha(1);
    }

    /**
     * Le symbole d'un atome sur sa sphère, comme dans une formule squelettique : les carbones et les hydrogènes
     * sous-entendus, les autres écrits ; les atomes qui réagissent, numérotés (tous, sur demande). Dessiné juste après sa
     * sphère : un atome plus proche le couvre.
     */
    private void letter(GraphicsContext g, int i) {
        int z = path.z[i];
        boolean numbered = path.reacting[i] || allLabels;
        if (!numbered && (z == 6 || z == 1)) return;
        double r = radius(i) * screen[i][2];
        if (r < 6.5) return;
        String symbol = symbol(z), index = numbered ? String.valueOf(path.number(i)) : "";
        Font big = font(Plan.SANS_SEMIBOLD, Math.min(21, Math.max(8, r * .95))), small = font(Plan.MEDIUM, Math.min(12, Math.max(6.5, r * .52)));
        double ws = width(symbol, big), wi = index.isEmpty() ? 0 : width(index, small) + .5;
        double x = screen[i][0] - (ws + wi) / 2, y = screen[i][1] + big.getSize() * .34;
        Color body = charges ? chargeColour(charge(i)) : tone(z);
        double luminance = .2126 * body.getRed() + .7152 * body.getGreen() + .0722 * body.getBlue();
        boolean light = luminance > .6;
        double fade = (1 - .5 * veil(screen[i][3]) / (double) (FOG_LEVELS - 1)) * presence(i);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        if (!light) {
            g.setFill(Color.color(0, 0, 0, .35 * fade));
            g.setFont(big);
            g.fillText(symbol, x, y + .8);
            if (!index.isEmpty()) {
                g.setFont(small);
                g.fillText(index, x + ws + .5, y + r * .22 + .8);
            }
        }
        g.setFill(light ? Color.web("#0A1526", .82 * fade) : Color.web("#FFFFFF", .95 * fade));
        g.setFont(big);
        g.fillText(symbol, x, y);
        if (!index.isEmpty()) {
            g.setFont(small);
            g.fillText(index, x + ws + .5, y + r * .22);
        }
    }

    /** La direction où s'écartent les tubes d'une liaison multiple : ⊥ à la liaison et à sa part π, sinon au regard. */
    private double[] side(double[] u, double[] piAxis) {
        double[] w = piAxis != null ? cross(u, piAxis) : null;
        if (w == null || norm(w) < .2) {
            w = cross(u, toward);
            if (norm(w) < .2) w = cross(u, up);
        }
        return unit(w);
    }

    // ------------------------------------------------------------------ le verre, calculé pixel par pixel
    /** La lumière principale (x à droite, y vers le bas, z vers l'œil) : en haut à gauche, devant ; et le demi-vecteur de Blinn. */
    private static final double[] KEY = unit(new double[]{-.46, -.62, .64});
    private static final double[] HALF = unit(new double[]{KEY[0], KEY[1], KEY[2] + 1});
    /** La lumière d'appoint, faible, d'en bas à droite : son petit reflet. */
    private static final double[] FILL = unit(new double[]{.55, .5, .67});
    private static final double[] FILL_HALF = unit(new double[]{FILL[0], FILL[1], FILL[2] + 1});
    /** Les rayons (px de l'écran) des sphères calculées : chaque taille montrée prend la suivante, réduite (≤ √2). */
    private static final double[] MIPS = {5, 7, 10, 14, 20, 28, 40, 56, 80, 112};
    private static final int STRIP_CORE = 36, STRIP_MARGIN = 2, ANGLES = 48;
    private final Map<Long, Image> sprites = new HashMap<>(), strips = new HashMap<>();

    private static int mip(double devicePx) {
        for (int k = 0; k < MIPS.length; k++) if (MIPS[k] >= devicePx) return k;
        return MIPS.length - 1;
    }

    private Image sphere(Color body, int veil, int mip) {
        long key = (long) pack(body) << 12 | (long) veil << 6 | mip;
        Image image = sprites.get(key);
        if (image == null) {
            if (sprites.size() > 1500) sprites.clear();
            image = sphere(body, veil * FOG / (FOG_LEVELS - 1), MIPS[mip]);
            sprites.put(key, image);
        }
        return image;
    }

    private Image strip(Color colour, double angle, int veil) {
        int bucket = (int) Math.floorMod(Math.round(angle / (2 * Math.PI) * ANGLES), ANGLES);
        long key = (long) pack(colour) << 16 | (long) veil << 8 | bucket;
        Image image = strips.get(key);
        if (image == null) {
            if (strips.size() > 6000) strips.clear();
            image = strip(colour, 2 * Math.PI * bucket / ANGLES, veil * FOG / (FOG_LEVELS - 1));
            strips.put(key, image);
        }
        return image;
    }

    /**
     * Une sphère de verre teinté de rayon `radius` px, pixel par pixel, comme une bille de verre devant la nuit : le
     * verre épais, sombre de sa teinte (un peu plus clair au cœur, où il est le plus épais) ; la lumière principale, venue
     * d'en haut à gauche, que la bille concentre de l'autre côté (une lueur de sa teinte, en bas à droite) ; le liseré de
     * Fresnel, (1 − cos)^3, mince et clair ; le reflet net de la lumière principale et son halo, en haut à gauche, et
     * celui, petit, de la lumière d'appoint en bas à droite ; puis le voile `fog` vers le fond. Dehors, un contour sombre
     * de 1,6 px la détache de ce qui est derrière.
     */
    static WritableImage sphere(Color body, double fog, double radius) {
        int margin = 3, size = (int) Math.ceil(2 * radius) + 2 * margin;
        WritableImage image = new WritableImage(size, size);
        PixelWriter out = image.getPixelWriter();
        double c = size / 2.0;
        double[] base = {body.getRed(), body.getGreen(), body.getBlue()};
        double[] deep = new double[3], glow = new double[3], rim = new double[3], veil = {VEIL.getRed(), VEIL.getGreen(), VEIL.getBlue()};
        for (int k = 0; k < 3; k++) {
            deep[k] = base[k] * .4;
            glow[k] = base[k] + (1 - base[k]) * .18;
            rim[k] = base[k] + (1 - base[k]) * .6;
        }
        double[] rgb = new double[3];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double px = (x + .5 - c) / radius, py = (y + .5 - c) / radius;
                double r = Math.sqrt(px * px + py * py);
                double cover = clamp((1 - r) * radius + .5);
                double ring = clamp(1 - (r - 1) * radius / 1.6) * clamp((r - 1) * radius + 1.5) * .55;
                if (cover <= 0 && ring <= 0) {
                    out.setArgb(x, y, 0);
                    continue;
                }
                double nx = r > 1 ? px / r : px, ny = r > 1 ? py / r : py;
                double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                double ndh = Math.max(0, nx * HALF[0] + ny * HALF[1] + nz * HALF[2]);
                double ndf = Math.max(0, nx * FILL_HALF[0] + ny * FILL_HALF[1] + nz * FILL_HALF[2]);
                double spec = Math.pow(ndh, 150) * 1.25 + Math.pow(ndh, 22) * .16 + Math.pow(ndf, 300) * .14;
                double fresnel = Math.pow(1 - nz, 3);
                double dx = nx - .16, dy = ny - .24;
                double caustic = Math.exp(-(dx * dx + dy * dy) / (2 * .42 * .42));
                double band = Math.exp(-sq((r - .84) / .08));             // un peu plus sombre juste avant le liseré
                for (int k = 0; k < 3; k++) {
                    double v = deep[k] * (.7 + .3 * nz) * (1 - .3 * band) + glow[k] * .95 * caustic + rim[k] * .9 * fresnel + spec;
                    rgb[k] = clamp(v) * (1 - fog) + veil[k] * fog;
                }
                // La sphère (couverture `cover`) sur son contour sombre (dehors seulement).
                double under = ring * (1 - cover), alpha = cover + under;
                int a = (int) Math.round(255 * clamp(alpha));
                if (a == 0) {
                    out.setArgb(x, y, 0);
                    continue;
                }
                int red = (int) Math.round(255 * clamp((rgb[0] * cover + OUTLINE.getRed() * under) / alpha));
                int green = (int) Math.round(255 * clamp((rgb[1] * cover + OUTLINE.getGreen() * under) / alpha));
                int blue = (int) Math.round(255 * clamp((rgb[2] * cover + OUTLINE.getBlue() * under) / alpha));
                out.setArgb(x, y, a << 24 | red << 16 | green << 8 | blue);
            }
        }
        return image;
    }

    /**
     * Une tranche de bâtonnet de verre (2 px de long, STRIP_CORE px de travers, plus une marge transparente) : son profil
     * en travers, éclairé comme les sphères pour une liaison d'angle `angle` à l'écran — le verre sombre de sa teinte, le
     * reflet net le long du côté tourné vers la lumière, la lumière concentrée le long de l'autre, le liseré de Fresnel
     * sur ses deux bords.
     */
    static WritableImage strip(Color colour, double angle, double fog) {
        int h = STRIP_CORE + 2 * STRIP_MARGIN;
        WritableImage image = new WritableImage(2, h);
        PixelWriter out = image.getPixelWriter();
        double ax = -Math.sin(angle), ay = Math.cos(angle);      // le travers de l'image (y), à l'écran
        // Où la lumière principale arrive sur le travers (+1 : du côté t > 0) : la lumière concentrée est de l'autre côté.
        double toward = KEY[0] * ax + KEY[1] * ay;
        double far = -.42 * Math.signum(toward) * Math.min(1, Math.abs(toward) * 2.2);
        double[] base = {colour.getRed(), colour.getGreen(), colour.getBlue()}, veil = {VEIL.getRed(), VEIL.getGreen(), VEIL.getBlue()};
        for (int y = 0; y < h; y++) {
            double t = ((y - STRIP_MARGIN) + .5) / STRIP_CORE * 2 - 1;
            double cover = clamp((1 - Math.abs(t)) * STRIP_CORE / 2 + .5);
            int argb = 0;
            if (cover > 0) {
                double tt = Math.max(-1, Math.min(1, t)), nz = Math.sqrt(1 - tt * tt);
                double nx = tt * ax, ny = tt * ay;
                double ndh = Math.max(0, nx * HALF[0] + ny * HALF[1] + nz * HALF[2]);
                double spec = Math.pow(ndh, 60) * .95 + Math.pow(ndh, 12) * .1;
                double fresnel = Math.pow(1 - nz, 2.6);
                double caustic = Math.exp(-sq((tt - far) / .38));
                int[] channel = new int[3];
                for (int k = 0; k < 3; k++) {
                    double deep = base[k] * .5, glow = base[k] + (1 - base[k]) * .2, rim = base[k] + (1 - base[k]) * .6;
                    double v = deep * (.75 + .25 * nz) + glow * .6 * caustic + rim * .85 * fresnel + spec;
                    channel[k] = (int) Math.round(255 * (clamp(v) * (1 - fog) + veil[k] * fog));
                }
                // Le verre laisse voir le fond en son milieu, moins sur les bords (le regard rasant) et sous les reflets.
                double opacity = Math.min(1, .7 + .3 * Math.pow(1 - nz, 1.2) + .4 * spec + .3 * caustic);
                int a = (int) Math.round(255 * clamp(cover * opacity));
                argb = a << 24 | channel[0] << 16 | channel[1] << 8 | channel[2];
            }
            out.setArgb(0, y, argb);
            out.setArgb(1, y, argb);
        }
        return image;
    }

    /** Le côté (px) d'un bout de capsule de lueur. */
    private static final int GLOW_CAP = 32;

    /**
     * Une capsule de lueur (un bout de GLOW_CAP px, 2 px de milieu, l'autre bout ; 2 × GLOW_CAP de haut) : sa couleur,
     * l'opacité en cloche de Gauss de la distance au segment central (σ = 0,3 de la demi-largeur), nulle au bord.
     */
    static WritableImage glowStrip(Color colour) {
        int w = 2 * GLOW_CAP + 2, h = 2 * GLOW_CAP;
        WritableImage image = new WritableImage(w, h);
        PixelWriter out = image.getPixelWriter();
        int rgb = (int) Math.round(colour.getRed() * 255) << 16 | (int) Math.round(colour.getGreen() * 255) << 8
                | (int) Math.round(colour.getBlue() * 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cx = x + .5 < GLOW_CAP ? x + .5 - GLOW_CAP : x + .5 > GLOW_CAP + 2 ? x + .5 - GLOW_CAP - 2 : 0;
                double d = Math.hypot(cx, y + .5 - GLOW_CAP) / GLOW_CAP;
                int a = (int) Math.round(255 * Math.exp(-d * d / (2 * .3 * .3)) * clamp((1 - d) * 8));
                out.setArgb(x, y, a << 24 | rgb);
            }
        }
        return image;
    }

    /** Une couleur sur 6 bits par canal (les images calculées sont partagées entre atomes de même teinte). */
    private static int pack(Color c) {
        return (int) Math.round(c.getRed() * 63) << 12 | (int) Math.round(c.getGreen() * 63) << 6 | (int) Math.round(c.getBlue() * 63);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double sq(double v) {
        return v * v;
    }

    /**
     * La couleur d'un élément sur le bleu nuit : la teinte de la table CPK (Jmol), sa saturation bornée et sa lumière
     * relevée — le carbone en graphite bleuté, l'hydrogène blanc, l'oxygène rouge corail, l'azote bleu roi.
     */
    static Color tone(int z) {
        Color named = switch (z) {
            case 1 -> HYDROGEN;
            case 6 -> CARBON;
            case 7 -> Color.web("#4A7BF7");
            case 8 -> Color.web("#F04E5E");
            case 9 -> Color.web("#8FE06E");
            case 14 -> Color.web("#D9B88E");
            case 15 -> Color.web("#FF9A45");
            case 16 -> Color.web("#F2CF4A");
            case 17 -> Color.web("#5EDB7C");
            default -> null;
        };
        if (named != null) return named;
        Color c = MoleculeScene.elementColor(z);
        return Color.hsb(c.getHue(), Math.min(c.getSaturation(), .72), Math.max(c.getBrightness(), .74));
    }

    /** Le carbone, graphite bleuté (le gris de la table CPK, refroidi pour la nuit) ; l'hydrogène, blanc. */
    private static final Color CARBON = Color.web("#8AA6C1"), HYDROGEN = Color.web("#D5DFE9");

    /** La couleur d'une charge de Mulliken : argent au neutre, cyan δ+, magenta δ− (la convention du laboratoire). */
    static Color chargeColour(double q) {
        double level = Math.round(q / .025) * .025;                 // par pas de 0,025 e : les sphères calculées se partagent
        double t = Math.min(1, Math.pow(Math.abs(level) / .45, .7));
        return level < 0 ? MoleculeScene.NEUTRAL.interpolate(MoleculeScene.RICH, t) : MoleculeScene.NEUTRAL.interpolate(MoleculeScene.POOR, t);
    }

    // ================================================================== les trajectoires
    /**
     * Les trajectoires des atomes qui réagissent : un point par image calculée, nettes sur le chemin imposé, pâles pour
     * l'approche et le départ (des translations de molécules entières), en tirets à travers un saut.
     */
    private void trails(GraphicsContext g) {
        double[] s = new double[4], prev = new double[4];
        g.setLineCap(StrokeLineCap.ROUND);
        for (int i = 0; i < path.z.length; i++) {
            if (!path.reacting[i] || i >= shown.length) continue;
            Color c = tone(path.z[i]).interpolate(Color.WHITE, .35);
            for (int f = 0; f < path.count(); f++) {
                project(path.frames[f][i], s);
                boolean scan = f >= path.scanStart && f < path.scanEnd;
                if (f > 0) {
                    boolean jump = path.jumps.contains(f - 1);
                    boolean inside = scan && f - 1 >= path.scanStart;
                    g.setStroke(c.deriveColor(0, 1, 1, jump ? .2 : inside ? .3 : .08));
                    g.setLineWidth(1);
                    g.setLineDashes(jump ? new double[]{2, 4} : null);
                    g.strokeLine(prev[0], prev[1], s[0], s[1]);
                    g.setLineDashes(null);
                }
                double d = scan ? 1.8 : 1.2;
                g.setFill(c.deriveColor(0, 1, 1, scan ? .55 : .16));
                g.fillOval(s[0] - d, s[1] - d, 2 * d, 2 * d);
                System.arraycopy(s, 0, prev, 0, 4);
            }
        }
    }

    // ================================================================== le dessus
    private static final String[] SYMBOLS = new String[119];

    private static String symbol(int z) {
        if (z < 0 || z >= SYMBOLS.length) return MoleculeView.symbolOf(z);
        if (SYMBOLS[z] == null) SYMBOLS[z] = MoleculeView.symbolOf(z);
        return SYMBOLS[z];
    }

    /** Les flèches courbes du dessin : d'un doublet (un atome) ou d'une liaison, vers un atome ou une liaison. */
    private void curly(GraphicsContext g) {
        if (path.curly.length == 0) return;
        double[] centre = new double[3];
        int count = 0;
        for (int i = 0; i < shown.length; i++) {
            if (!path.reacting[i]) continue;
            for (int k = 0; k < 3; k++) centre[k] += shown[i][k];
            count++;
        }
        if (count > 0) centre = scale(centre, 1.0 / count);
        for (int[] arrow : path.curly) {
            double[] from = end(arrow[0], arrow[1], true), to = end(arrow[2], arrow[3], false);
            if (from == null || to == null) continue;
            double[] chord = sub(to, from);
            double length = norm(chord);
            if (length < 1e-3) continue;
            double[] mid = scale(add(from, to), .5);
            double[] out = sub(mid, centre);
            out = sub(out, scale(chord, dot(out, chord) / (length * length)));
            if (norm(out) < .1) out = cross(chord, toward);
            out = unit(out);
            double[] control = add(mid, scale(out, .35 * length + .35));
            int m = 28;
            double[] xs = new double[m + 1], ys = new double[m + 1];
            double[] s = new double[4];
            for (int k = 0; k <= m; k++) {
                double t = k / (double) m;
                double[] p = add(add(scale(from, (1 - t) * (1 - t)), scale(control, 2 * t * (1 - t))), scale(to, t * t));
                project(p, s);
                xs[k] = s[0];
                ys[k] = s[1];
            }
            g.setLineCap(StrokeLineCap.ROUND);
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.setStroke(INK_DARK.deriveColor(0, 1, 1, .55));
            g.setLineWidth(4);
            g.strokePolyline(xs, ys, m + 1);
            g.setStroke(CURLY);
            g.setLineWidth(1.7);
            g.strokePolyline(xs, ys, m + 1);
            double hx = xs[m] - xs[m - 3], hy = ys[m] - ys[m - 3], hl = Math.hypot(hx, hy);
            if (hl > 1e-6) {
                hx /= hl;
                hy /= hl;
                double size = 9;
                g.setFill(CURLY);
                g.fillPolygon(new double[]{xs[m] + hx * 2, xs[m] - hx * size - hy * size * .45, xs[m] - hx * size + hy * size * .45},
                        new double[]{ys[m] + hy * 2, ys[m] - hy * size + hx * size * .45, ys[m] - hy * size - hx * size * .45}, 3);
            }
        }
    }

    /** Un bout de flèche courbe : le milieu d'une liaison, ou un atome (son doublet, à l'écart de ses voisins). */
    private double[] end(int a, int b, boolean source) {
        int n = shown.length;
        if (a < 0 || a >= n) return null;
        if (b >= 0 && b < n) return scale(add(shown[a], shown[b]), .5);
        double[] away = new double[3];
        for (MoleculeScene.Bond bond : pose.bonds) {
            int other = bond.a() == a ? bond.b() : bond.b() == a ? bond.a() : -1;
            if (other < 0 || other >= n || bond.order() < .3) continue;
            away = add(away, unit(sub(shown[a], shown[other])));
        }
        if (norm(away) < .1) away = up.clone();
        return add(shown[a], scale(unit(away), radius(a) + (source ? .35 : .15)));
    }

    /** Un texte lisible sur tout fond : un contour sombre et doux sous ses lettres. */
    private static void halo(GraphicsContext g, String text, double x, double y, Font font, Color colour) {
        g.setFont(font);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.setStroke(INK_DARK.deriveColor(0, 1, 1, .78));
        g.setLineWidth(3.4);
        g.strokeText(text, x, y);
        g.setFill(colour);
        g.fillText(text, x, y);
    }

    /**
     * Les liaisons qui changent : « 2,47 Å  0,09 » — la longueur mesurée et l'indice de Mayer, là, maintenant — près du
     * milieu, du côté hors de la molécule. Pour une liaison dont seul l'ordre change, « 2 → 1  1,79 » sur demande ou au
     * survol. Sans place, elle se tait.
     */
    private void labels(GraphicsContext g) {
        List<double[]> taken = new ArrayList<>();
        for (int i = 0; i < shown.length; i++) {
            if (!drawn[i]) continue;
            double r = radius(i) * screen[i][2] * .9;
            taken.add(new double[]{screen[i][0] - r, screen[i][1] - r, 2 * r, 2 * r});
        }
        double[] centre = {0, 0};
        int count = 0;
        for (int i = 0; i < shown.length; i++) {
            if (!drawn[i]) continue;
            centre[0] += screen[i][0];
            centre[1] += screen[i][1];
            count++;
        }
        if (count > 0) {
            centre[0] /= count;
            centre[1] /= count;
        }
        Font strong = font(Plan.SANS_SEMIBOLD, 12), small = font(Plan.SANS, 11);
        Integer[] order = new Integer[path.changes.length];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, (p, q) -> Integer.compare(p == hoveredChange ? 0 : 1, q == hoveredChange ? 0 : 1));
        g.setTextBaseline(VPos.BASELINE);
        for (int k : order) {
            int[] c = path.changes[k];
            int a = c[0], b = c[1];
            if (!visible(a) || !visible(b)) continue;
            int kind = Path.kind(c);
            double m = mayer(k);
            if (kind == 0 && (m < .05 || !(allLabels || k == hoveredChange))) continue;
            String first = kind == 0 ? c[2] + " → " + c[3] : MoleculeFormat.decimal(dist(pose.p[a], pose.p[b]), 2) + " Å";
            String second = "  " + MoleculeFormat.decimal(m, 2);
            Color colour = kind > 0 ? FORMING : kind < 0 ? BREAKING : CHANGED;
            double mx = (screen[a][0] + screen[b][0]) / 2, my = (screen[a][1] + screen[b][1]) / 2;
            double dx = screen[b][0] - screen[a][0], dy = screen[b][1] - screen[a][1], l = Math.max(1e-6, Math.hypot(dx, dy));
            double nx = -dy / l, ny = dx / l;
            if ((mx - centre[0]) * nx + (my - centre[1]) * ny < 0) {
                nx = -nx;
                ny = -ny;
            }
            double wFirst = width(first, strong), w = wFirst + width(second, small), h = 14;
            double[] spot = null;
            for (double reachPx : new double[]{16, 26, 38, -16, -28}) {
                double x = mx + nx * reachPx - w / 2 + nx * w / 2 * .8, y = my + ny * reachPx - h / 2 + ny * h / 2 * .6;
                double[] box = {x - 2, y - 2, w + 4, h + 4};
                if (free(box, taken)) {
                    spot = box;
                    break;
                }
            }
            if (spot == null) {
                if (k != hoveredChange) continue;
                spot = new double[]{mx + nx * 16 - w / 2 - 2, my + ny * 16 - h / 2 - 2, w + 4, h + 4};
            }
            taken.add(spot);
            g.setTextAlign(TextAlignment.LEFT);
            halo(g, first, spot[0] + 2, spot[1] + 13, strong, colour);
            halo(g, second, spot[0] + 2 + wFirst, spot[1] + 13, small, Plan.INK2);
        }
    }

    private static boolean free(double[] box, List<double[]> taken) {
        for (double[] t : taken) {
            if (box[0] < t[0] + t[2] && t[0] < box[0] + box[2] && box[1] < t[1] + t[3] && t[1] < box[1] + box[3]) return false;
        }
        return true;
    }

    /** Pendant un saut de branche : ce qu'il est, en haut de la vue. */
    /** Ce qui se passe à travers le saut qui suit l'image k (PathMotion.jump), donné par la vue du chemin. */
    java.util.function.IntFunction<double[]> jumpCause;

    private void jumpNote(GraphicsContext g) {
        int k = (int) Math.floor(pose.s);
        boolean relay = path.relay && path.relayNote != null;
        if (!relay && (path.jumps.isEmpty() || !path.jumps.contains(k))) return;
        String head = I18n.text(relay ? "Relais" : "Saut");
        double[] cause = relay || jumpCause == null ? null : jumpCause.apply(k);
        String text;
        if (relay) {
            text = path.relayNote;
        } else if (cause != null && cause[0] >= 0) {
            // Calculé sur les deux images : l'atome qui bouge le plus dans sa molécule, et l'énergie.
            int atom = (int) cause[0];
            text = path.name(atom) + " " + I18n.text("bouge d'un coup de") + " " + MoleculeFormat.decimal(cause[1], 2)
                    + " Å, " + I18n.text("énergie") + " " + MoleculeFormat.signed(cause[2], 2) + " eV · "
                    + I18n.text("passage non calculé, montré en fondu");
        } else {
            text = I18n.text("passage non calculé, montré en fondu");
        }
        Font bold = font(Plan.SANS_SEMIBOLD, 12), font = font(Plan.SANS, 12);
        // À gauche, sous les étapes : la barre de la vue, en haut à droite, garde sa place.
        double room = Math.max(160, width - 290);
        String shownText = fit(text, room - width(head, bold) - 48, font);
        double tw = width(head, bold) + width(shownText, font) + 46;
        double x = 16, y = 16;
        card(g, x, y, tw, 30, 15);
        g.setFill(CURLY);
        g.fillOval(x + 14, y + 12, 6, 6);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        g.setFont(bold);
        g.setFill(Plan.INK);
        g.fillText(head, x + 28, y + 19.5);
        g.setFont(font);
        g.setFill(Plan.INK2);
        g.fillText(shownText, x + 34 + width(head, bold), y + 19.5);
    }

    private static void card(GraphicsContext g, double x, double y, double w, double h, double radius) {
        g.setFill(Color.color(0, 0, 0, .2));
        g.fillRoundRect(x, y + 2, w, h, 2 * radius, 2 * radius);
        g.setFill(CARD);
        g.fillRoundRect(x, y, w, h, 2 * radius, 2 * radius);
        g.setStroke(CARD_EDGE);
        g.setLineWidth(1);
        g.strokeRoundRect(x + .5, y + .5, w - 1, h - 1, 2 * radius, 2 * radius);
    }

    // ================================================================== la légende, l'échelle
    static final double LEGEND_W = 236;

    /** La légende, sur son calque : redessinée seulement quand ce qu'elle dit (ou la taille) change. */
    private void paintLegend(boolean ready) {
        String key = ready ? System.identityHashCode(path) + "," + width + "," + height + "," + charges + curly + trails + legendFolded
                + "," + pixels + "," + stereo + "," + I18n.text("Légende") : "";
        if (key.equals(legendKey)) return;
        legendKey = key;
        GraphicsContext g = legendLayer.getGraphicsContext2D();
        g.clearRect(0, 0, legendLayer.getWidth(), legendLayer.getHeight());
        if (ready) {
            legend(g);
            if (stereo == Stereo.SIDE) {
                // Côte à côte : la même légende devant chaque œil.
                g.save();
                g.translate(eyeWidth, 0);
                legend(g);
                g.restore();
            }
        }
    }

    /** Chaque signe présent, en quelques mots ; d'où il vient est dit au survol et dans les « ? » du panneau. */
    private void legend(GraphicsContext g) {
        Font title = font(Plan.SANS_SEMIBOLD, 12), body = font(Plan.SANS, 11), small = font(Plan.SANS, 10);
        double lw = Math.min(LEGEND_W, width - 32), x0 = 16, row = 20;
        List<String> kinds = new ArrayList<>(), texts = new ArrayList<>();
        boolean forms = false, breaks = false, changes = false;
        for (int[] c : path.changes) {
            int kind = Path.kind(c);
            forms |= kind > 0;
            breaks |= kind < 0;
            changes |= kind == 0;
        }
        kinds.add("stick");
        texts.add(I18n.text("liaison : 1 à 3 bâtonnets (Mayer)"));
        if (forms) {
            kinds.add("form");
            texts.add(I18n.text("se forme : éclat selon Mayer"));
        }
        if (breaks) {
            kinds.add("break");
            texts.add(I18n.text("se rompt : éclat selon Mayer"));
        }
        if (changes) {
            kinds.add("change");
            texts.add(I18n.text("son ordre change"));
        }
        if (charges) {
            kinds.add("charge");
            texts.add(I18n.text("charge de Mulliken"));
        }
        if (curly && path.curly.length > 0) {
            kinds.add("curly");
            texts.add(I18n.text("flèche courbe du dessin"));
        }
        if (trails) {
            kinds.add("trail");
            texts.add(I18n.text("trajectoire calculée"));
        }
        int[] elements = Arrays.stream(path.z).distinct().boxed().sorted((a, b) -> a == 1 ? 1 : b == 1 ? -1 : Integer.compare(b, a))
                .mapToInt(Integer::intValue).toArray();
        int elementRows = charges ? 0 : (elements.length + 1) / 2;
        double headH = 32;
        legendTitleH = headH;
        double lh = legendFolded ? 30 : headH + elementRows * row + (elementRows > 0 ? 8 : 0) + kinds.size() * row + 24;
        legendW = legendFolded ? width(I18n.text("Légende"), title) + 46 : lw;
        legendH = lh;
        legendX = x0;
        legendY = height - 16 - lh;
        card(g, legendX, legendY, legendW, legendH, 12);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        g.setFont(title);
        g.setFill(Plan.INK);
        g.fillText(I18n.text("Légende"), legendX + 14, legendY + (legendFolded ? 19.5 : 21));
        chevron(g, legendX + legendW - 20, legendY + (legendFolded ? 15 : 17), legendFolded);
        if (legendFolded) return;
        double y = legendY + headH + 12;
        Font symbolFont = font(Plan.MEDIUM, 11);
        for (int k = 0; k < (charges ? 0 : elements.length); k++) {
            double x = legendX + 14 + (k % 2) * (lw - 28) / 2, yy = y + (k / 2) * row;
            orb(g, x + 6, yy - 4, 6.2, tone(elements[k]));
            g.setFont(symbolFont);
            g.setFill(Plan.INK);
            String symbol = MoleculeView.symbolOf(elements[k]);
            g.fillText(symbol, x + 17, yy);
            g.setFont(body);
            g.setFill(Plan.INK2);
            g.fillText(fit(MoleculeFormat.element(elements[k]), (lw - 28) / 2 - 24 - width(symbol, symbolFont), body),
                    x + 21 + width(symbol, symbolFont), yy);
        }
        if (elementRows > 0) {
            y += elementRows * row + 6;
            g.setStroke(Color.web("#FFFFFF", .06));
            g.setLineWidth(1);
            g.strokeLine(legendX + 14, y - 10, legendX + lw - 14, y - 10);
        }
        for (int k = 0; k < kinds.size(); k++) {
            double x = legendX + 14, mid = y - 4;
            switch (kinds.get(k)) {
                case "stick" -> rod(g, x, mid, 24, 4.6, GLASS.interpolate(tone(6), .42), 1);
                case "change" -> rod(g, x, mid, 24, 4.6, GLASS.interpolate(tone(6), .42).interpolate(CHANGED, .82), 1);
                case "form" -> filament(g, x, mid, 24, FORMING);
                case "break" -> filament(g, x, mid, 24, BREAKING);
                case "charge" -> {
                    orb(g, x + 4, mid, 4.8, chargeColour(.5));
                    orb(g, x + 12, mid, 4.8, chargeColour(0));
                    orb(g, x + 20, mid, 4.8, chargeColour(-.5));
                }
                case "curly" -> {
                    g.setStroke(CURLY);
                    g.setLineWidth(1.6);
                    g.beginPath();
                    g.moveTo(x, mid + 3);
                    g.quadraticCurveTo(x + 12, mid - 8, x + 24, mid);
                    g.stroke();
                }
                case "trail" -> {
                    g.setFill(Plan.INK2);
                    for (int j = 0; j < 4; j++) g.fillOval(x + j * 7 - 1.4, mid - 1.4, 2.8, 2.8);
                }
                default -> { }
            }
            g.setFont(body);
            g.setFill(Plan.INK2);
            g.fillText(fit(texts.get(k), lw - 66, body), legendX + 48, y);
            y += row;
        }
        g.setFont(small);
        g.setFill(Plan.INK3);
        g.fillText(fit(I18n.text("verre et reflets : décor"), lw - 28, small), legendX + 14, y + 4);
    }

    private static void chevron(GraphicsContext g, double x, double y, boolean folded) {
        g.setStroke(Plan.INK2);
        g.setLineWidth(1.4);
        g.setLineCap(StrokeLineCap.ROUND);
        if (folded) g.strokePolyline(new double[]{x - 2, x + 2, x - 2}, new double[]{y - 4, y, y + 4}, 3);
        else g.strokePolyline(new double[]{x - 4, x, x + 4}, new double[]{y - 2, y + 2, y - 2}, 3);
    }

    /** Un clic sur le titre de la légende la plie ou la déplie ; vrai s'il a servi. */
    boolean legendClick(double x, double y) {
        x = eyeX(x);
        if (x >= legendX && x <= legendX + legendW && y >= legendY && y <= legendY + legendTitleH) {
            legendFolded = !legendFolded;
            framed = false;
            dirty = true;
            return true;
        }
        return false;
    }

    /** Une petite sphère de la légende : la même sphère de verre calculée que dans la vue. */
    private void orb(GraphicsContext g, double x, double y, double r, Color body) {
        Image sprite = sphere(body, 0, mip(r * pixels));
        double size = sprite.getWidth() * r / MIPS[mip(r * pixels)];
        g.drawImage(sprite, x - size / 2, y - size / 2, size, size);
    }

    /** Un tube de la légende : la même tranche de verre, posée à plat. */
    private void rod(GraphicsContext g, double x, double y, double length, double thick, Color colour, double alpha) {
        Image strip = strip(colour, 0, 0);
        double across = thick / STRIP_CORE * strip.getHeight();
        g.setGlobalAlpha(alpha);
        g.drawImage(strip, x, y - across / 2, length, across);
        g.setGlobalAlpha(1);
    }

    /** Un filament de la légende : du fil à peine visible au tube plein, et sa lueur. */
    private void filament(GraphicsContext g, double x, double y, double length, Color colour) {
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(colour.deriveColor(0, 1, 1, .2));
        g.setLineWidth(7);
        g.setLineCap(StrokeLineCap.ROUND);
        g.strokeLine(x + 3, y, x + length - 3, y);
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        int parts = 6;
        for (int k = 0; k < parts; k++) {
            double s = (k + 1) / (double) parts;
            Color glass = GLASS.interpolate(tone(6), .42).interpolate(colour, .62);
            rod(g, x + k * length / parts, y, length / parts + .4, 4.6 * (.24 + .76 * Math.sqrt(s)), glass, .45 + .55 * s);
        }
    }

    /** 1 Å à la profondeur de la cible, en bas à droite. */
    private void scaleBar(GraphicsContext g) {
        double length = zoom();
        if (length < 12 || length > width / 3) return;
        double x1 = width - 24, x0 = x1 - length, y = height - 26;
        g.setStroke(Color.web("#FFFFFF", .45));
        g.setLineWidth(1);
        g.setLineCap(StrokeLineCap.BUTT);
        g.strokeLine(x0, y, x1, y);
        g.strokeLine(x0, y - 3, x0, y + 3);
        g.strokeLine(x1, y - 3, x1, y + 3);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BASELINE);
        halo(g, "1 Å", (x0 + x1) / 2, y - 7, font(Plan.SANS, 10.5), Plan.INK2);
    }

    // ================================================================== le survol
    /** L'atome sous le curseur (le plus proche de l'œil), ou −1. */
    int pick(double x, double y) {
        x = eyeX(x);
        if (pose == null || screen.length != shown.length) return -1;
        int best = -1;
        double bestDepth = 1e9;
        for (int i = 0; i < shown.length; i++) {
            if (!drawn[i]) continue;
            double r = Math.max(4, radius(i) * screen[i][2]) + 2;
            if (Math.hypot(x - screen[i][0], y - screen[i][1]) <= r && screen[i][3] < bestDepth) {
                bestDepth = screen[i][3];
                best = i;
            }
        }
        return best;
    }

    /** La liaison qui change sous le curseur (à 7 px de son axe), ou −1. */
    int pickChange(double x, double y) {
        x = eyeX(x);
        if (pose == null || screen.length != shown.length) return -1;
        for (int k = 0; k < path.changes.length; k++) {
            int a = path.changes[k][0], b = path.changes[k][1];
            if (a >= screen.length || b >= screen.length) continue;
            if (segment(x, y, screen[a][0], screen[a][1], screen[b][0], screen[b][1]) < 7) return k;
        }
        return -1;
    }

    /** La fiche de ce qui est survolé : un atome (sa charge, ses liaisons ici), ou une liaison qui change. */
    private void hover(GraphicsContext g) {
        List<String[]> lines = new ArrayList<>();           // {texte, genre} — t titre, n normal, f forme, r rompt, s sous-titre
        if (hovered >= 0 && hovered < shown.length) {
            int i = hovered;
            lines.add(new String[]{path.name(i) + "  " + MoleculeFormat.element(path.z[i]), "t"});
            if (pose.q != null && i < pose.q.length) {
                String start = path.charges.length > 0 && i < path.charges[0].length
                        ? "   " + I18n.text("au départ") + " " + MoleculeFormat.signed(path.charges[0][i], 2) + " e" : "";
                lines.add(new String[]{I18n.text("charge de Mulliken") + " " + MoleculeFormat.signed(pose.q[i], 2) + " e" + start, "s"});
            }
            List<MoleculeScene.Bond> own = new ArrayList<>();
            for (MoleculeScene.Bond bond : pose.bonds) if ((bond.a() == i || bond.b() == i) && bond.order() >= .1) own.add(bond);
            own.sort((a, b) -> Double.compare(b.order(), a.order()));
            for (MoleculeScene.Bond bond : own.subList(0, Math.min(6, own.size()))) {
                int other = bond.a() == i ? bond.b() : bond.a();
                int k = path.change(i, other);
                String kind = k < 0 ? "n" : Path.kind(path.changes[k]) > 0 ? "f" : Path.kind(path.changes[k]) < 0 ? "r" : "n";
                lines.add(new String[]{path.name(other) + "   "
                        + MoleculeFormat.decimal(dist(pose.p[i], pose.p[other]), 2) + " Å   " + I18n.text("Mayer") + " "
                        + MoleculeFormat.decimal(bond.order(), 2), kind});
            }
        } else if (hoveredChange >= 0 && hoveredChange < path.changes.length) {
            int[] c = path.changes[hoveredChange];
            int kind = Path.kind(c);
            String what = kind > 0 ? I18n.text("se forme") : kind < 0 ? I18n.text("se rompt") : I18n.text("son ordre change");
            lines.add(new String[]{path.name(c[0]) + "–" + path.name(c[1]) + "  " + what, "t"});
            lines.add(new String[]{MoleculeFormat.decimal(dist(pose.p[c[0]], pose.p[c[1]]), 2) + " Å   " + I18n.text("Mayer") + " "
                    + MoleculeFormat.decimal(mayer(hoveredChange), 2), kind > 0 ? "f" : kind < 0 ? "r" : "n"});
            lines.add(new String[]{I18n.text("calculés aux images du chemin, interpolés entre elles"), "s"});
            lines.add(new String[]{I18n.text("le dessin : ordre") + " " + c[2] + " → " + c[3], "s"});
        }
        if (lines.isEmpty()) return;
        Font title = font(Plan.SANS_SEMIBOLD, 12.5), body = font(Plan.SANS, 11.5), sub = font(Plan.SANS, 10.5);
        double cw = 0;
        for (String[] line : lines) cw = Math.max(cw, width(line[0], line[1].equals("t") ? title : line[1].equals("s") ? sub : body));
        cw = Math.min(360, cw + 28);
        double ch = 16 + lines.size() * 18 + 4;
        double x = Math.max(10, Math.min(width - cw - 10, eyeX(mouseX) + 18)), y = Math.max(10, Math.min(height - ch - 10, mouseY + 18));
        card(g, x, y, cw, ch, 12);
        double yy = y + 24;
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.BASELINE);
        for (String[] line : lines) {
            String kind = line[1];
            Font font = kind.equals("t") ? title : kind.equals("s") ? sub : body;
            g.setFont(font);
            g.setFill(kind.equals("t") ? Plan.INK : kind.equals("f") ? FORMING : kind.equals("r") ? BREAKING
                    : kind.equals("s") ? Plan.INK3 : Plan.INK2);
            g.fillText(fit(line[0], cw - 28, font), x + 14, yy);
            yy += 18;
        }
    }

    // ================================================================== les nombres
    /** L'indice de Mayer du changement k à cette géométrie : calculé par le moteur (liaisons de la pose), sinon l'image. */
    double mayer(int k) {
        int[] c = path.changes[k];
        MoleculeScene.Bond bond = bond(c[0], c[1]);
        if (bond != null) return bond.order();
        if (pose != null && pose.bonds != null && !pose.bonds.isEmpty()) return 0;   // sous 0,05 : le moteur ne l'envoie pas
        int f = (int) Math.max(0, Math.min(path.count() - 1, Math.round(pose == null ? 0 : pose.s)));
        return k < path.orders[f].length ? path.orders[f][k] : 0;
    }

    private MoleculeScene.Bond bond(int a, int b) {
        if (pose == null) return null;
        for (MoleculeScene.Bond bond : pose.bonds) {
            if (bond.a() == a && bond.b() == b || bond.a() == b && bond.b() == a) return bond;
        }
        return null;
    }

    // ================================================================== le texte
    private final Map<String, Font> fonts = new HashMap<>();
    private final Map<String, Double> widths = new HashMap<>();

    /** Une police, sa taille arrondie au demi-point (gardée : la chercher à chaque image coûte). */
    private Font font(String family, double size) {
        double rounded = Math.round(size * 2) / 2.0;
        return fonts.computeIfAbsent(family + '\u0001' + rounded, k -> Font.font(family, rounded));
    }

    /** La largeur d'un texte (gardée : la mise en page d'un texte coûte des dizaines de µs). */
    private double width(String text, Font font) {
        String key = font.getName() + '\u0001' + font.getSize() + '\u0001' + text;
        Double known = widths.get(key);
        if (known != null) return known;
        probe.setFont(font);
        probe.setText(text);
        double w = probe.getLayoutBounds().getWidth();
        if (widths.size() > 5000) widths.clear();
        widths.put(key, w);
        return w;
    }

    private String fit(String text, double room, Font font) {
        if (room <= 0) return "";
        if (width(text, font) <= room) return text;
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }

    // ================================================================== les vecteurs
    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] scale(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] unit(double[] a) {
        double n = norm(a);
        return n < 1e-12 ? new double[]{0, 0, 1} : scale(a, 1 / n);
    }

    private static double segment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }
}
