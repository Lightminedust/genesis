package org.genesis.subatomic;

import org.genesis.app.I18n;
import org.genesis.fusion.ReactorView;

import com.studio.genesis.grpc.HadronChannel;
import com.studio.genesis.grpc.HadronLabState;
import com.studio.genesis.grpc.HadronProduct;
import com.studio.genesis.grpc.HadronSpinState;
import com.studio.genesis.grpc.SmParticle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * L'atelier de hadrons. On choisit un moule — baryon (trois emplacements), méson (un quark « + » et un antiquark « − ») ou libre
 * (états exotiques) —, on y glisse des quarks pris dans le tableau du Modèle Standard, on choisit leur couleur (clic) ; le sac prend
 * la couleur du mélange additif de ses quarks (rouge + vert = jaune ; + bleu = blanc ; rouge + antirouge = blanc) : le jeu est de
 * faire du blanc, qui est exactement la règle de neutralité de couleur. Un lepton ou un boson est repoussé (il ne porte pas de
 * couleur). Au test, le moteur applique les règles (neutralité, Pauli, nombre de quarks), et la charge, le nombre baryonique et le
 * spin s'écrivent sous le sac comme des additions dont les termes viennent des quarks eux-mêmes ; puis le destin — stable,
 * désintégration forte, électromagnétique ou faible, dislocation d'un état exotique, ou top qui meurt avant d'exister.
 */
public final class HadronBench {
    interface Actions {
        void test(int spinIndex, double[] triples);
        void decay();
        void clear();
    }

    /** Un quark posé : sa saveur, antiquark ?, sa couleur (0 rouge, 1 vert, 2 bleu) et son emplacement dans le moule. */
    record Slot(String id, boolean anti, int colour, int place) {
        Slot(String id, boolean anti, int colour) {
            this(id, anti, colour, -1);
        }
    }

    static final Map<String, Integer> CODES = Map.ofEntries(Map.entry("u", 0), Map.entry("d", 1), Map.entry("s", 2), Map.entry("c", 3),
            Map.entry("b", 4), Map.entry("t", 5), Map.entry("e", 10), Map.entry("mu", 11), Map.entry("tau", 12), Map.entry("nu_e", 13),
            Map.entry("nu_mu", 14), Map.entry("nu_tau", 15), Map.entry("g", 20), Map.entry("gamma", 21), Map.entry("Z", 22),
            Map.entry("W", 23), Map.entry("H", 24));
    static final double TABLE_WIDTH = 410, RESULT_WIDTH = 372, GAP = 14;
    /** Les moules. */
    static final int BARYON = 0, MESON = 1, FREE = 2;
    static final String[] COLOUR_NAMES = {"rouge", "vert", "bleu"}, ANTI_NAMES = {"antirouge (cyan)", "antivert (magenta)", "antibleu (jaune)"};

    private final Canvas canvas = new Canvas();
    private final Pane root = new Pane(canvas);
    private final SmTable table;
    private final Actions actions;
    private final List<Slot> slots = new ArrayList<>();
    private HadronLabState result;
    private List<Slot> tested = List.of();
    private String dragging;
    private double dragX, dragY, mouseX = -1, mouseY = -1, clock, benchX, benchY, benchR, benchRight, resultClock = -99;
    private int resultRun = -1, spinIndex, mode = BARYON;
    private final SubRender render = new SubRender();
    private final List<double[]> screen = new ArrayList<>();
    private double previousX, previousY;
    private boolean rotating;
    private final List<double[]> stateRows = new ArrayList<>();
    // le refus animé : la particule repoussée revient à sa case, l'emplacement vibre en rouge, et l'on dit pourquoi
    private String bounceId;
    private double bounceX, bounceY, bounceStart = -99;
    private int bouncePlace = -1;
    private String notice = "";
    private double noticeStart = -99;
    private IntConsumer onMode = m -> {};
    private boolean noticeOk;
    private double zoom = 1;                // la molette : 1 = la vue d'ensemble
    private double massTop;                 // où finit le texte du verdict (la masse se pose dessous s'il reste la place)
    // la corde qu'on étire à la souris : le quark tiré, son déplacement (fm), le retour élastique quand on lâche
    private int pressHit = -1, stretching = -1, snapQuark = -1;
    private double pressX, pressY, snapStart = -99;
    private MouseButton pressButton = MouseButton.PRIMARY;
    private double[] offset = {0, 0, 0}, snap = {0, 0, 0};
    /** La tension de la corde (MeV/fm, la même que dans les collisions) et le prix d'une paire u ū ou d d̄ (masses constituantes). */
    static final double SIGMA = 900, LIGHT_PAIR = 2 * 336, STRANGE_PAIR = 2 * 486;
    // les gluons échangés : la couleur montrée de chaque quark tourne, le total reste blanc
    private final java.util.Random rng = new java.util.Random(11);
    private int[] shown = new int[0];
    private List<Slot> shownFor = List.of();
    private double pulseStart;
    private int pulseFrom = 0, pulseTo = 1;
    /** Un méson arraché par une corde qui casse : son contenu, son nom, d'où il part, sa direction, quand. */
    private record Flight(List<Slot> content, String name, double[] start, double[] dir, double t0) {}
    private final List<Flight> flights = new ArrayList<>();

    HadronBench(SmTable table, Actions actions) {
        this.table = table;
        this.actions = actions;
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        canvas.setOnMousePressed(e -> {
            String id = table.at(e.getX(), e.getY());
            if (id != null) {
                table.select(id);
                dragging = id;
                dragX = e.getX();
                dragY = e.getY();
                return;
            }
            previousX = e.getX();
            previousY = e.getY();
            int hit = slotAt(e.getX(), e.getY());
            rotating = hit < 0 && e.getX() > GAP * 2 + TABLE_WIDTH && e.getX() < canvas.getWidth() - RESULT_WIDTH - GAP * 2;
            if (hit >= 0) {
                pressHit = hit;
                pressX = e.getX();
                pressY = e.getY();
                pressButton = e.getButton();
                return;
            }
            for (int i = 0; i < stateRows.size(); i++) {
                double[] r = stateRows.get(i);
                if (e.getX() >= r[0] && e.getX() <= r[0] + r[2] && e.getY() >= r[1] && e.getY() <= r[1] + r[3]) {
                    spinIndex = i;
                    test();
                    return;
                }
            }
        });
        canvas.setOnMouseDragged(e -> {
            dragX = e.getX();
            dragY = e.getY();
            if (pressHit >= 0 && pressButton == MouseButton.PRIMARY && (stretching >= 0 || Math.hypot(e.getX() - pressX, e.getY() - pressY) > 6)) {
                pull(e.getX(), e.getY());
                return;
            }
            if (dragging == null && rotating) render.camera.drag(e.getX() - previousX, e.getY() - previousY);
            previousX = e.getX();
            previousY = e.getY();
        });
        canvas.setOnMouseReleased(e -> {
            if (pressHit >= 0) {
                if (stretching >= 0) {
                    // on lâche : la corde ramène le quark (un retour élastique amorti)
                    snap = offset.clone();
                    snapQuark = stretching;
                    snapStart = clock;
                    stretching = -1;
                    offset = new double[3];
                } else if (pressHit < slots.size()) {
                    Slot s = slots.get(pressHit);
                    if (e.getButton() == MouseButton.SECONDARY) {
                        if (mode == MESON) say(I18n.text("Dans le moule méson, l'emplacement « + » reçoit la matière, « − » l'antimatière ; le bouton ")
                                + I18n.text("« antimatière » échange les deux."));
                        else slots.set(pressHit, new Slot(s.id(), !s.anti(), s.colour(), s.place()));
                    } else if (e.getClickCount() >= 2) {
                        slots.remove(pressHit);
                        if (mode == FREE) renumber();
                    } else slots.set(pressHit, new Slot(s.id(), s.anti(), (s.colour() + 1) % 3, s.place()));
                }
                pressHit = -1;
                return;
            }
            if (dragging != null && Math.hypot(e.getX() - benchX, e.getY() - benchY) < benchR * 1.6) drop(dragging, e.getX(), e.getY());
            dragging = null;
            rotating = false;
        });
        canvas.setOnScroll(e -> zoom = Math.max(.45, Math.min(1.8, zoom * Math.exp(-e.getDeltaY() / 400))));
        canvas.setOnMouseMoved(e -> {
            mouseX = e.getX();
            mouseY = e.getY();
            table.hover(table.at(e.getX(), e.getY()));
        });
    }

    Pane getNode() { return root; }

    List<Slot> slots() { return slots; }

    int mode() { return mode; }

    void setOnMode(IntConsumer c) { onMode = c; }

    /** Le nombre d'emplacements du moule. */
    int flightsCount() { return flights.size(); }

    int capacity() { return mode == BARYON ? 3 : mode == MESON ? 2 : 5; }

    /** Change de moule (la paillasse est vidée). */
    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        slots.clear();
        result = null;
        onMode.accept(mode);
    }

    private void renumber() {
        for (int i = 0; i < slots.size(); i++) slots.set(i, new Slot(slots.get(i).id(), slots.get(i).anti(), slots.get(i).colour(), i));
    }

    private void say(String text) {
        notice = text;
        noticeStart = clock;
        noticeOk = false;
    }

    private void tell(String text) {
        say(text);
        noticeOk = true;
    }

    /** Le point d'attache de la corde d'un quark : la jonction du Y (baryon), ou l'autre quark (méson). */
    private double[] anchorOf(int i) {
        if (slots.size() == 2) return positionOf(slots.get(1 - i));
        return new double[]{0, 0, 0};
    }

    /** Où est montré un quark : son emplacement, plus l'étirement qu'on lui impose, ou son retour élastique. */
    private double[] displaced(int i) {
        double[] p = positionOf(slots.get(i));
        if (i == stretching) return SubRender.add(p, offset);
        double t = clock - snapStart;
        if (i == snapQuark && t < 1.5) return SubRender.add(p, SubRender.scale(snap, Math.exp(-t * 5) * Math.cos(t * 18)));
        return p;
    }

    /**
     * Tirer un quark à la souris : la corde s'allonge, elle stocke σ·ΔL (σ = 0,9 GeV/fm). Dès que cette énergie paie une paire
     * quark–antiquark (2 × 336 MeV pour u ū ou d d̄, 2 × 486 MeV pour s s̄), la corde casse.
     */
    private void pull(double x, double y) {
        boolean quarksOnly = slots.stream().allMatch(sl -> isQuark(sl.id()));
        if (pressHit >= slots.size() || !quarksOnly || slots.size() < 2 || slots.size() > 3) {
            if (stretching < 0 && slots.size() < 2) say("Un quark seul n'a pas de corde à tirer : il en faut au moins deux.");
            pressHit = -1;
            return;
        }
        stretching = pressHit;
        snapQuark = -1;
        double[] base = positionOf(slots.get(stretching));
        double sc = Math.max(1e-6, render.scaleAt(base));
        offset = SubRender.add(SubRender.scale(render.camera.right(), (x - pressX) / sc), SubRender.scale(render.camera.up(), -(y - pressY) / sc));
        double stored = stored(stretching);
        if (stored >= LIGHT_PAIR) breakString(stretching, stored);
    }

    /** Pour les tests et les aperçus : tendre la corde du quark i de `offsetFm` (fm), comme à la souris. */
    void stretch(int i, double[] offsetFm) {
        if (i < 0 || i >= slots.size()) return;
        pressHit = i;
        stretching = i;
        offset = offsetFm.clone();
        double stored = stored(i);
        if (stored >= LIGHT_PAIR) breakString(i, stored);
    }

    /** L'énergie stockée dans la corde d'un quark tiré : σ fois son allongement. */
    private double stored(int i) {
        double[] anchor = anchorOf(i), base = positionOf(slots.get(i));
        double rest = SubRender.norm(SubRender.sub(base, anchor)), length = SubRender.norm(SubRender.sub(displaced(i), anchor));
        return SIGMA * Math.max(0, length - rest);
    }

    /**
     * La corde casse : son énergie devient une paire f f̄ (u et d à égalité, s supprimé d'un facteur ≈ 0,3 par l'effet tunnel,
     * exp(−πm²/σ) — et seulement si l'énergie la paie). Le quark tiré part avec le nouveau partenaire de nature opposée : un
     * méson, blanc ; à sa place reste le nouveau quark, de même couleur : le hadron reste blanc, mais son contenu a changé.
     */
    private void breakString(int i, double stored) {
        Slot s = slots.get(i);
        double r = rng.nextDouble();
        String f = stored >= STRANGE_PAIR && r < .13 ? "s" : r < .565 ? "u" : "d";
        double[] where = displaced(i), dir = offset.clone();
        double n = SubRender.norm(dir);
        dir = n > 1e-9 ? SubRender.scale(dir, 1 / n) : new double[]{1, 0, 0};
        String quark = s.anti() ? f : s.id(), antiquark = s.anti() ? s.id() : f;
        String name = mesonName(quark, antiquark);
        flights.add(new Flight(List.of(new Slot(s.id(), s.anti(), s.colour(), 0), new Slot(f, !s.anti(), s.colour(), 1)), name, where, dir, clock));
        slots.set(i, new Slot(f, s.anti(), s.colour(), s.place()));
        stretching = -1;
        pressHit = -1;
        offset = new double[3];
        StringBuilder rest = new StringBuilder();
        for (Slot sl : slots) rest.append(sl.anti() ? sl.id().toUpperCase(Locale.ROOT) : sl.id());
        tell(String.format(I18n.locale(), I18n.text("La corde a cassé : σ·ΔL = %.0f MeV paie une paire %s%s̄ (%s MeV). Le quark arraché part avec ")
                + I18n.text("l'%s dans un %s — on n'isole jamais un quark. Il reste %s : teste-le."), stored, f, f,
                f.equals("s") ? "2 × 486" : "2 × 336", s.anti() ? "quark " + f : "antiquark " + f + "̄", name, pretty(rest.toString())));
    }

    /** Le nom d'un méson de quark q et d'antiquark ā. */
    static String mesonName(String q, String a) {
        return switch (q + a) {
            case "ud" -> "π⁺";
            case "du" -> "π⁻";
            case "uu", "dd" -> "π⁰";
            case "us" -> "K⁺";
            case "ds" -> "K⁰";
            case "su" -> "K⁻";
            case "sd" -> "K̄⁰";
            case "ss" -> "φ (s s̄)";
            case "cu" -> "D⁰";
            case "cd" -> "D⁺";
            case "uc" -> "D̄⁰";
            case "dc" -> "D⁻";
            case "cs" -> "D_s⁺";
            case "sc" -> "D_s⁻";
            case "ub" -> "B⁺";
            case "db" -> "B⁰";
            case "bu" -> "B⁻";
            case "bd" -> "B̄⁰";
            default -> q + " " + a + "̄";
        };
    }

    private static boolean isQuark(String id) {
        return id != null && id.length() == 1 && "udscbt".contains(id);
    }

    /** Pourquoi une particule qui n'est pas un quark ne peut pas entrer dans un hadron. */
    private String refusal(String id) {
        SmParticle p = table.particle(id);
        String symbol = p == null ? id : p.getSymbol();
        if (id.equals("g")) return I18n.text("Le gluon porte la couleur, mais c'est lui la colle : il est déjà dans le sac (ce sont les cordes). ")
                + I18n.text("On ne le pose pas comme un quark.");
        if (p != null && p.getFamily().equals("lepton")) return symbol + I18n.text(" est un lepton : il n'a pas de charge de couleur. L'interaction ")
                + I18n.text("forte ne le voit pas — rien ne peut le lier dans un hadron.");
        return symbol + I18n.text(" n'a pas de charge de couleur : il ne ressent pas l'interaction forte et ne peut pas être un constituant d'un hadron.");
    }

    /** La place libre visée par un dépôt en (x, y) : l'emplacement vide le plus proche à l'écran ; −1 si le moule est plein. */
    private int targetPlace(double x, double y) {
        if (mode == FREE) return slots.size() < 5 ? slots.size() : -1;
        int best = -1;
        double bestD = 1e18;
        for (int place = 0; place < capacity(); place++) {
            final int pl = place;
            if (slots.stream().anyMatch(s -> s.place() == pl)) continue;
            double[] sp = render.project(slotPosition(place, capacity()));
            double d = sp == null ? 1e17 : Math.hypot(x - sp[0], y - sp[1]);
            if (d < bestD) {
                bestD = d;
                best = place;
            }
        }
        return best;
    }

    /** Déposer une particule : un quark prend sa place ; tout le reste est repoussé, avec la raison. */
    void drop(String id, double x, double y) {
        int place = targetPlace(x, y);
        if (!isQuark(id)) {
            bounceId = id;
            bounceX = x;
            bounceY = y;
            bounceStart = clock;
            bouncePlace = place >= 0 ? place : 0;
            say(refusal(id));
            return;
        }
        if (place < 0) {
            say(mode == FREE ? "Cinq constituants au plus. Double clic sur un quark pour le retirer."
                    : "Le moule est plein. Double clic sur un quark pour le retirer, ou change de moule.");
            return;
        }
        boolean anti = mode == MESON && place == 1;
        slots.add(new Slot(id, anti, colourFor(place, anti), place));
    }

    /** La couleur d'un quark qu'on pose : celle qui manque au mélange (méson : celle du partenaire, en anticouleur pour l'antiquark). */
    private int colourFor(int place, boolean anti) {
        if (mode == MESON) {
            for (Slot s : slots) if (s.place() != place) return s.colour();
            return 0;
        }
        boolean[] used = new boolean[3];
        for (Slot s : slots) if (s.anti() == anti) used[s.colour() % 3] = true;
        for (int c = 0; c < 3; c++) if (!used[c]) return c;
        return 0;
    }

    /**
     * Remplace la paillasse par un contenu : minuscules = quarks, MAJUSCULES = antiquarks, « _ » = emplacement laissé vide ; le moule
     * suit le contenu (trois quarks ou trois antiquarks : baryon ; un quark et un antiquark : méson ; sinon libre).
     */
    void load(String content) {
        slots.clear();
        boolean lower = content.chars().allMatch(ch -> ch == '_' || Character.isLowerCase(ch));
        boolean upper = content.chars().allMatch(ch -> ch == '_' || Character.isUpperCase(ch));
        int previous = mode;
        mode = content.length() == 3 && (lower || upper) ? BARYON : content.length() == 2 && (!lower && !upper || content.contains("_")) ? MESON : FREE;
        if (mode == MESON) {
            // le quark à l'emplacement « + », l'antiquark à « − »
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '_') continue;
                slots.add(new Slot(String.valueOf(Character.toLowerCase(ch)), Character.isUpperCase(ch), 0, Character.isUpperCase(ch) ? 1 : 0));
            }
        } else {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch != '_') slots.add(new Slot(String.valueOf(Character.toLowerCase(ch)), Character.isUpperCase(ch), 0, mode == FREE ? slots.size() : i));
            }
        }
        neutralColours();
        spinIndex = 0;
        if (mode != previous) onMode.accept(mode);
    }

    /** Couleurs neutres : quark + antiquark de même couleur, puis R, V, B pour ce qui reste — la même règle que le moteur. */
    void neutralColours() {
        List<Integer> quarks = new ArrayList<>(), antis = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) (slots.get(i).anti() ? antis : quarks).add(i);
        int[] colour = new int[slots.size()];
        int k = 0;
        while (!quarks.isEmpty() && !antis.isEmpty()) {
            colour[quarks.remove(quarks.size() - 1)] = k % 3;
            colour[antis.remove(antis.size() - 1)] = k % 3;
            k++;
        }
        for (List<Integer> group : List.of(quarks, antis)) for (int i = 0; i < group.size(); i++) colour[group.get(i)] = i % 3;
        for (int i = 0; i < slots.size(); i++) slots.set(i, new Slot(slots.get(i).id(), slots.get(i).anti(), colour[i], slots.get(i).place()));
    }

    /** Matière ↔ antimatière ; dans le moule méson, le quark et l'antiquark échangent aussi leurs emplacements « + » et « − ». */
    void conjugate() {
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int place = mode == MESON ? 1 - s.place() : s.place();
            slots.set(i, new Slot(s.id(), !s.anti(), s.colour(), place));
        }
    }

    void clear() {
        slots.clear();
        result = null;
        actions.clear();
    }

    /** Pour les aperçus : l'assemblage présent est celui que le moteur a analysé. */
    void markTested() {
        tested = List.copyOf(slots);
    }

    void test() {
        double[] triples = new double[slots.size() * 3];
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            triples[3 * i] = CODES.getOrDefault(s.id(), 99);
            triples[3 * i + 1] = s.anti() ? 1 : 0;
            triples[3 * i + 2] = s.colour();
        }
        tested = List.copyOf(slots);
        actions.test(spinIndex, triples);
    }

    void apply(HadronLabState state) {
        if (state == null || !state.getActive()) {
            result = null;
            return;
        }
        if (state.getRun() != resultRun) {
            resultRun = state.getRun();
            resultClock = clock;                    // l'horloge continue : les animations partent de l'arrivée du verdict
        }
        result = state;
        spinIndex = state.getChosenState();
    }

    private int slotAt(double x, double y) {
        for (int i = 0; i < slots.size() && i < screen.size(); i++) {
            double[] p = screen.get(i);
            if (p != null && Math.hypot(x - p[0], y - p[1]) < Math.max(18, p[2] + 6)) return i;
        }
        return -1;
    }

    /** La place d'un quark posé, en fm : seul au centre ; deux sur un axe ; trois en triangle ; quatre en tétraèdre ; cinq en bipyramide. */
    static double[] slotPosition(int i, int n) {
        double r = .5;
        return switch (n) {
            case 1 -> new double[]{0, 0, 0};
            case 2 -> new double[]{i == 0 ? -.45 : .45, 0, 0};
            case 3 -> new double[]{r * Math.cos(-Math.PI / 2 + 2 * Math.PI * i / 3), -r * Math.sin(-Math.PI / 2 + 2 * Math.PI * i / 3), 0};
            case 4 -> {
                double[][] t = {{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}};
                yield new double[]{t[i][0] * r * .62, t[i][1] * r * .62, t[i][2] * r * .62};
            }
            default -> i < 3 ? new double[]{r * Math.cos(2 * Math.PI * i / 3), 0, r * Math.sin(2 * Math.PI * i / 3)}
                    : new double[]{0, i == 3 ? .62 : -.62, 0};
        };
    }

    /** Où est un quark posé (fm) : l'emplacement du moule, ou, en libre, sa place parmi les présents. */
    private double[] positionOf(Slot s) {
        return mode == FREE ? slotPosition(s.place(), Math.max(1, slots.size())) : slotPosition(s.place(), capacity());
    }

    // ================================================================== la couleur : une synthèse additive
    /**
     * Le mélange des couleurs des quarks posés, en synthèse additive : rouge (1, 0, 0), vert (0, 1, 0), bleu (0, 0, 1) ; un antiquark
     * porte l'anticouleur, le complémentaire — antirouge = cyan (0, 1, 1). Le mélange est blanc (trois composantes égales) si et
     * seulement si chaque couleur a le même compte net (couleurs moins anticouleurs) : c'est exactement la neutralité de couleur
     * qu'exige l'interaction forte (R + V + B, ou couleur + anticouleur).
     */
    static double[] mix(List<Slot> slots) {
        double[] c = new double[3];
        for (Slot s : slots) {
            if (!isQuark(s.id())) continue;
            int k = s.colour() % 3;
            if (!s.anti()) c[k] += 1;
            else for (int j = 0; j < 3; j++) if (j != k) c[j] += 1;
        }
        return c;
    }

    static boolean white(double[] c) {
        return c[0] > 0 && Math.abs(c[0] - c[1]) < 1e-9 && Math.abs(c[1] - c[2]) < 1e-9;
    }

    /** La couleur à l'écran d'un mélange (normalisée sur sa plus forte composante). */
    static Color mixColour(double[] c) {
        double m = Math.max(c[0], Math.max(c[1], c[2]));
        if (m <= 0) return Color.web("#CFE0F2");
        return Color.color(c[0] / m, c[1] / m, c[2] / m);
    }

    private static String mixName(double[] c) {
        double m = Math.max(c[0], Math.max(c[1], c[2]));
        int r = (int) Math.round(2 * c[0] / m), g = (int) Math.round(2 * c[1] / m), b = (int) Math.round(2 * c[2] / m);
        if (r == 2 && g == 2 && b == 2) return "blanc";
        if (r == 2 && g == 0 && b == 0) return "rouge";
        if (r == 0 && g == 2 && b == 0) return "vert";
        if (r == 0 && g == 0 && b == 2) return "bleu";
        if (r == 2 && g == 2 && b == 0) return "jaune";
        if (r == 0 && g == 2 && b == 2) return "cyan";
        if (r == 2 && g == 0 && b == 2) return "magenta";
        return "teinté";
    }

    /** Ce que dit le mélange, et ce qu'il faut pour faire du blanc. */
    private String mixAdvice(double[] c) {
        long filled = slots.stream().filter(s -> isQuark(s.id())).count();
        if (filled == 0) return mode == MESON ? "Glisse un quark sur « + », puis un quark sur « − » (il devient l'antiquark)."
                : "Glisse des quarks dans le moule : leurs couleurs s'ajoutent.";
        if (white(c)) return "Blanc ✓ — neutre en couleur : l'interaction forte peut les lier.";
        double max = Math.max(c[0], Math.max(c[1], c[2]));
        List<String> missing = new ArrayList<>(), excess = new ArrayList<>();
        double min = Math.min(c[0], Math.min(c[1], c[2]));
        for (int k = 0; k < 3; k++) {
            if (c[k] < max - 1e-9) missing.add(COLOUR_NAMES[k]);
            if (c[k] > min + 1e-9 && c[k] >= max - 1e-9) excess.add(COLOUR_NAMES[k]);
        }
        String name = mixName(c);
        if (filled < (mode == FREE ? 0 : capacity())) {
            if (mode == MESON) {
                Slot q = slots.stream().filter(s -> !s.anti()).findFirst().orElse(null);
                return capital(name) + I18n.text(" : l'antiquark devra porter l'anticouleur — ") + (q == null ? "celle du quark" : ANTI_NAMES[q.colour() % 3])
                        + I18n.text(" — pour faire du blanc.");
            }
            return capital(name) + I18n.text(" : il manque du ") + String.join(" et du ", missing) + I18n.text(" — ajoute un quark ") + String.join(" ou ", missing) + ".";
        }
        return capital(name) + I18n.text(" : pas blanc. Trop de ") + String.join(" et de ", excess) + I18n.text(", pas assez de ") + String.join(" et de ", missing)
                + I18n.text(" — clic sur un quark pour changer sa couleur.");
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    void tick(double dt) {
        clock += Math.min(.05, dt);
        render.clock = clock;
        render.camera.tick(Math.min(.05, dt), rotating, -2, 4);
        draw();
    }

    // ================================================================== le dessin
    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 4 || h < 4) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        DocStyle.background(g, w, h);
        double tableH = SmTable.height(TABLE_WIDTH);
        DocStyle.panel(g, GAP, GAP, TABLE_WIDTH, h - 2 * GAP);
        DocStyle.label(g, "le modèle standard", GAP + 16, GAP + 22, 11, TextAlignment.LEFT, 1);
        if (table.ready()) {
            table.draw(g, GAP, GAP + 22, TABLE_WIDTH);
            drawCard(g, GAP + 16, GAP + 26 + tableH + 8, TABLE_WIDTH - 32, h - tableH - 70);
        }
        double left = GAP * 2 + TABLE_WIDTH, right = w - RESULT_WIDTH - GAP * 2;
        benchRight = right;
        // la paillasse : une scène 3D entre le tableau et le verdict (le fond est déjà peint)
        render.centreShiftY = -h * .06;                                // le sac un peu plus haut : l'arithmétique dessous
        // une vue d'ensemble : le sac, ses cordes, ses gluons et ce qui l'entoure ; la molette rapproche ou éloigne
        render.begin(g, w, h, left, right - left, h, new double[]{0, 0, 0}, 8 * zoom, false);
        double[] c = render.project(new double[]{0, 0, 0});
        benchX = c == null ? (left + right) / 2 : c[0];
        benchY = c == null ? h / 2 : c[1];
        benchR = 1.4 * (c == null ? 80 : c[3]);
        drawMixer(g, left + 64, GAP + 70, left + 14, right - left);
        drawBench(g);
        drawDecay(g, left, right, h);
        drawArithmetic(g, left, right);
        render.drawTags();
        drawResult(g, w - RESULT_WIDTH - GAP, GAP, RESULT_WIDTH, h - 2 * GAP);
        // la masse, en bas du panneau du verdict
        if (massTop < h - 2 * GAP - 150) drawMass(g, w - RESULT_WIDTH - GAP + 18, h - GAP - 150, RESULT_WIDTH - 36);
        drawBounce(g);
        if (dragging != null) {
            SmParticle p = table.particle(dragging);
            if (p != null) {
                DocStyle.blob(g, dragX, dragY, 16, SmTable.ring(p.getFamily()), .9);
                g.setTextAlign(TextAlignment.CENTER);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
                g.setFill(Color.WHITE);
                I18n.fillText(g, p.getSymbol(), dragX, dragY + 5);
            }
        }
    }

    private void drawCard(GraphicsContext g, double x, double y, double w, double h) {
        SmParticle p = table.particle(table.selected());
        if (p == null) return;
        DocStyle.label(g, p.getName(), x, y + 14, 13, TextAlignment.LEFT, 1);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", 11));
        double cy = y + 34;
        g.setFill(DocStyle.PALE);
        I18n.fillText(g, String.format(I18n.locale(), "%s · spin %s · charge %s · antiparticule %s", p.getFamily().equals("quark") ? "quark"
                : p.getFamily().equals("lepton") ? "lepton" : p.getFamily().equals("scalar") ? "boson scalaire" : "boson vecteur",
                SmTable.spin(p.getSpin()), SmTable.charge(p.getCharge()), p.getAnti()), x, cy);
        cy += 20;
        for (String line : p.getLinesList()) {
            g.setFill(DocStyle.WHITE);
            cy += wrap(g, "·  " + line, x, cy, w, 15, Font.font("Segoe UI", 11)) + 3;
            if (cy > y + h) break;
        }
        g.setFill(DocStyle.DIM);
        if (p.getFamily().equals("quark")) wrap(g, "Glisse ce quark dans le moule pour construire un hadron.", x, Math.min(cy + 8, y + h), w, 14,
                Font.font("Segoe UI", 10.5));
        else wrap(g, "Pas de charge de couleur : il ne peut pas entrer dans un hadron.", x, Math.min(cy + 8, y + h), w, 14,
                Font.font("Segoe UI", 10.5));
    }

    private boolean resultMatches() {
        return result != null && tested.equals(slots);
    }

    private static final double PERIOD = 5.5, BURST = 1.4;

    /** Où en est l'animation de désintégration : −1 avant l'éclatement, puis 0 → 1. */
    private double decayPhase() {
        if (!resultMatches() || result.getProductsCount() == 0) return -1;
        double t = Math.max(0, clock - resultClock) % PERIOD;
        return t < BURST ? -1 : (t - BURST) / (PERIOD - BURST);
    }

    /** Le contenu d'un hadron (minuscules : quarks, MAJUSCULES : antiquarks) en positions (fm) autour d'un centre. */
    private static List<double[]> layout(String content, double[] centre, double scale) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < content.length(); i++) out.add(SubRender.add(centre, SubRender.scale(slotPosition(i, content.length()), scale)));
        return out;
    }

    /** Des couleurs neutres pour un contenu : quark + anticouleur, ou rouge-vert-bleu. */
    private static int[] neutral(String content) {
        int[] colours = new int[content.length()];
        int qi = 0, ai = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.length() == 2) colours[i] = 0;
            else colours[i] = Character.isLowerCase(content.charAt(i)) ? qi++ % 3 : ai++ % 3;
        }
        return colours;
    }

    private double bagRadius(int quarks) {
        return 1.11 * Math.pow(Math.max(1, quarks) / 3.0, .25);
    }

    /** La synthèse additive : chaque constituant est un disque de sa couleur ; là où ils se recouvrent, leurs couleurs s'ajoutent. */
    private void drawMixer(GraphicsContext g, double x, double y, double textX, double width) {
        List<Slot> quarks = slots.stream().filter(s -> isQuark(s.id())).toList();
        double[] c = mix(slots);
        int n = quarks.size();
        double r = 21;
        g.setGlobalBlendMode(BlendMode.ADD);
        for (int i = 0; i < n; i++) {
            Slot s = quarks.get(i);
            double a = -Math.PI / 2 + 2 * Math.PI * i / n, off = n == 1 ? 0 : 12;
            int k = s.colour() % 3;
            Color col = !s.anti() ? Color.color(k == 0 ? 1 : 0, k == 1 ? 1 : 0, k == 2 ? 1 : 0)
                    : Color.color(k == 0 ? 0 : 1, k == 1 ? 0 : 1, k == 2 ? 0 : 1);
            g.setFill(col.deriveColor(0, 1, 1, .78));
            g.fillOval(x + off * Math.cos(a) - r, y + off * Math.sin(a) - r, 2 * r, 2 * r);
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.setStroke(Color.web("#9FB0C8").deriveColor(0, 1, 1, .5));
        g.setLineWidth(1);
        g.strokeOval(x - 36, y - 36, 72, 72);
        if (white(c)) {
            g.setStroke(DocStyle.GOOD);
            g.setLineWidth(2);
            g.strokeOval(x - 36, y - 36, 72, 72);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.5));
        g.setFill(DocStyle.PALE);
        I18n.fillText(g, DocStyle.caps("la couleur du sac"), textX, y - 46);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(DocStyle.DIM);
        I18n.fillText(g, "synthèse additive : rouge + vert + bleu = blanc", textX, y + 50);
        g.setFill(white(c) ? DocStyle.GOOD : DocStyle.WHITE);
        wrap(g, mixAdvice(c), textX, y + 68, Math.min(140, width * .3), 15, Font.font("Segoe UI", 11.5));
    }

    /**
     * La paillasse en 3D : le moule (ses emplacements vides, en hologramme), les quarks posés, la membrane de leur sac — teintée du
     * mélange de leurs couleurs —, les cordes de gluons. Quand l'objet se désintègre, le sac du parent s'efface pendant que naissent
     * ceux des produits : leur union forme un col qui s'étrangle — une division cellulaire, dans les directions et aux vitesses
     * calculées.
     */
    private void drawBench(GraphicsContext g) {
        boolean matches = resultMatches();
        boolean valid = matches && result.getValid();
        double phase = decayPhase();
        double parent = phase < 0 ? 1 : Math.max(0, 1 - phase / .35);
        screen.clear();
        int n = slots.size();
        double[] colours = mix(slots);
        Color tint = mixColour(colours);
        boolean quarksOnly = slots.stream().allMatch(sl -> isQuark(sl.id()));
        List<double[]> at = new ArrayList<>();
        for (int i = 0; i < n; i++) at.add(displaced(i));
        double[] junction = {0, 0, 0};
        if (!slots.equals(shownFor)) {
            shown = new int[n];
            for (int i = 0; i < n; i++) shown[i] = slots.get(i).colour() % 3;
            shownFor = List.copyOf(slots);
            pulseStart = clock;
        }
        // la membrane : le sac du parent, puis ceux des produits s'il se désintègre
        List<SubRender.Blob> blobs = new ArrayList<>();
        List<SubRender.Capsule> tubes = new ArrayList<>();
        double excitation = matches && result.getWidthMev() > 0 ? result.getWidthMev() : 0;
        double pulse = Math.max(.035, valid ? Math.min(.15, .02 + excitation / 800) : 0);     // des bords qui fluctuent
        int size = mode == FREE ? Math.max(1, n) : capacity();
        if (parent > .01) {
            blobs.add(new SubRender.Blob(junction, bagRadius(Math.max(n, size)) * .8 * parent, pulse, 0));
            for (int i = 0; i < n; i++) {
                blobs.add(new SubRender.Blob(at.get(i), .43 * parent, pulse, i * 1.9));
                if (quarksOnly && n >= 3) tubes.add(new SubRender.Capsule(junction, at.get(i), .46 * parent));
            }
            if (quarksOnly && n == 2) tubes.add(new SubRender.Capsule(at.get(0), at.get(1), .46 * parent));
        }
        List<Object[]> products = phase >= 0 ? products(phase) : List.of();
        for (Object[] pr : products) {
            String content = (String) pr[1];
            double[] centre = (double[]) pr[2];
            if (content.isEmpty()) continue;
            double grow = Math.min(1, phase / .3);
            blobs.add(new SubRender.Blob(centre, bagRadius(content.length()) * .7 * grow, 0, centre[0]));
            for (double[] q : layout(content, centre, .7)) blobs.add(new SubRender.Blob(q, .35 * grow, 0, 0));
        }
        SubRender.Membrane bag = render.membraneBack(blobs, tubes, phase >= 0 ? Color.web("#CFE0F2") : tint, Math.min(1, excitation / 300),
                valid || !matches ? 1 : .55);
        drawMould(g, parent);
        if (parent > .3 && n >= 1) drawSea(bagRadius(Math.max(n, size)) * .78, parent * (quarksOnly ? 1 : .4));
        // les cordes de gluons du parent : des tubes de flux, plus vifs quand on les tend
        if (parent > .01 && quarksOnly && n >= 2) {
            double alpha = (matches ? (valid ? 1 : .35) : .7) * parent;
            if (n == 2) {
                double l = SubRender.norm(SubRender.sub(at.get(1), at.get(0)));
                render.fluxTube(at.get(0), at.get(1), .26, l / .9, alpha);
            } else for (int i = 0; i < n; i++) {
                double l = SubRender.norm(SubRender.sub(at.get(i), junction));
                render.fluxTube(junction, at.get(i), .26, l / .5, alpha);
            }
            if (stretching >= 0 && stretching < n) {
                double e = stored(stretching);
                double[] anchor = n == 2 ? at.get(1 - stretching) : junction;
                render.tag(SubRender.lerp(anchor, at.get(stretching), .5), .1, "corde tendue", String.format(I18n.locale(),
                        I18n.text("σ·ΔL = %.0f MeV  ·  une paire u ū coûte %.0f MeV"), e, LIGHT_PAIR), DocStyle.GOLD, 1);
            }
            drawGluons(at, junction, parent);
        }
        // les quarks posés, avec leur spin dans l'état choisi
        int[] spins = spinArrows();
        for (int i = 0; i < n; i++) {
            Slot sl = slots.get(i);
            SmParticle part = table.particle(sl.id());
            boolean quark = part != null && part.getFamily().equals("quark");
            int hue = i < shown.length ? shown[i] : sl.colour() % 3;
            Color c = quark ? DocStyle.colour(hue, sl.anti()) : (part == null ? Color.GRAY : SmTable.ring(part.getFamily()));
            double[] sp = render.project(at.get(i));
            screen.add(sp == null ? null : new double[]{sp[0], sp[1], .3 * sp[3]});
            if (parent <= .01) continue;
            double mass = part == null ? 336 : Math.max(2, part.getMassMev());
            // un quark est ponctuel : une petite bille, à peine plus grosse s'il est lourd (un codage de la masse, pas une taille)
            double radius = quark ? .15 + .028 * Math.log10(Math.max(1, mass)) : .15;
            render.quark(at.get(i), radius, c, (part == null ? sl.id() : part.getSymbol()) + (sl.anti() ? "̄" : ""), parent);
            if (quark) spinRing(at.get(i), radius * 1.55, spins != null && i < spins.length ? spins[i] : 0, c, parent);
            // (pas d'étiquette par quark : sa couleur se voit, sa charge est dans l'addition sous le sac — le sac reste lisible)
        }
        render.membraneFront(bag);
        // le spin total : un anneau autour du sac si J > 0 ; rien ne tourne si les spins se compensent (J = 0)
        if (spins != null && parent > .5) {
            double j = result.getStates(Math.max(0, Math.min(result.getStatesCount() - 1, result.getChosenState()))).getSpin();
            if (j > .1) spinRing(junction, bagRadius(Math.max(n, size)) * 1.08, 1, Color.web("#9FE8FF"), .8 * parent);
            render.tag(SubRender.add(junction, new double[]{0, -bagRadius(Math.max(n, size)) * 1.35, 0}), .1, "J = " + spinText(j),
                    j > .1 ? "les spins s'ajoutent : le hadron a un moment cinétique" : "les spins se compensent : aucun moment cinétique",
                    Color.web("#9FE8FF"), .9 * parent);
        }
        drawFlights();
        if (n == 0 && mode == FREE) {
            DocStyle.label(g, "dépose des quarks ici", benchX, benchY, 13, TextAlignment.CENTER, .85);
        }
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(DocStyle.DIM);
        I18n.fillText(g, "clic : couleur  ·  clic droit : antiquark  ·  double clic : retirer", benchRight - 8, GAP + 14);
        I18n.fillText(g, "glisser un quark : tendre sa corde  ·  vide : tourner  ·  molette : zoom", benchRight - 8, GAP + 28);
        if (matches) {
            String name = result.getName().isEmpty() ? (result.getValid() ? (result.getFateKind().equals("top") ? "aucun hadron possible"
                    : "état prédit") : "refusé") : CollisionScene.pretty(result.getName());
            DocStyle.label(g, name, benchX, benchY - benchR - 14, 18, TextAlignment.CENTER, Math.max(.4, parent));
        } else if (result != null && n > 0) {
            DocStyle.label(g, "modifié : teste à nouveau", benchX, benchY - benchR - 14, 12, TextAlignment.CENTER, .8);
        }
    }

    /**
     * Le moule, en hologramme : ses emplacements vides (anneaux pointillés qui respirent) — trois en triangle reliés au centre pour un
     * baryon, « + » (matière) et « − » (antimatière) pour un méson. Pendant qu'on glisse une particule, l'emplacement visé s'allume :
     * vert pour un quark, rouge pour ce qui ne porte pas de couleur. Un emplacement qui vient de repousser une particule vibre en rouge.
     */
    private void drawMould(GraphicsContext g, double alpha) {
        if (mode == FREE || alpha <= .01) return;
        int cap = capacity();
        int aimed = dragging != null && Math.hypot(dragX - benchX, dragY - benchY) < benchR * 1.6 ? targetPlace(dragX, dragY) : -1;
        boolean goodDrag = isQuark(dragging);
        double shake = clock - bounceStart < .7 ? Math.exp(-(clock - bounceStart) * 5) : 0;
        double[] centre = render.project(new double[]{0, 0, 0});
        for (int place = 0; place < cap; place++) {
            final int pl = place;
            boolean filled = slots.stream().anyMatch(s -> s.place() == pl);
            double[] sp = render.project(slotPosition(place, cap));
            if (sp == null) continue;
            double x = sp[0] + (place == bouncePlace ? 5 * shake * Math.sin(clock * 70) : 0), y = sp[1], r = .32 * sp[3];
            Color ring = place == bouncePlace && shake > .02 ? DocStyle.BAD : place == aimed ? (goodDrag ? DocStyle.GOOD : DocStyle.BAD)
                    : Color.web("#7FE3FF");
            double breathe = .55 + .25 * Math.sin(clock * 3 + place);
            if (mode == BARYON && centre != null) {
                g.setStroke(Color.web("#7FE3FF").deriveColor(0, 1, 1, .18 * alpha));
                g.setLineWidth(1);
                g.setLineDashes(3, 4);
                g.strokeLine(centre[0], centre[1], sp[0], sp[1]);
                g.setLineDashes((double[]) null);
            }
            if (filled && !(place == bouncePlace && shake > .02)) continue;
            if (place == aimed || (place == bouncePlace && shake > .02)) {
                g.setGlobalBlendMode(BlendMode.ADD);
                g.setFill(ring.deriveColor(0, 1, 1, .25 * alpha));
                g.fillOval(x - r * 1.5, y - r * 1.5, 3 * r, 3 * r);
                g.setGlobalBlendMode(BlendMode.SRC_OVER);
            }
            g.setStroke(ring.deriveColor(0, 1, 1, breathe * alpha));
            g.setLineWidth(1.6);
            g.setLineDashes(5, 4);
            g.strokeOval(x - r, y - r, 2 * r, 2 * r);
            g.setLineDashes((double[]) null);
            g.setTextAlign(TextAlignment.CENTER);
            g.setFill(ring.deriveColor(0, 1, 1, .9 * alpha));
            if (mode == MESON) {
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
                I18n.fillText(g, place == 0 ? "+" : "−", x, y + 6);
                g.setFont(Font.font("Segoe UI", 10.5));
                I18n.fillText(g, place == 0 ? "quark (matière)" : "antiquark (antimatière)", x, y + r + 15);
            } else {
                g.setFont(Font.font("Segoe UI", 10.5));
                I18n.fillText(g, "quark", x, y + 4);
            }
        }
    }

    /**
     * Les gluons échangés : une impulsion lumineuse court d'un quark à l'autre le long des cordes. Un gluon porte une couleur et une
     * anticouleur (rouge-antivert…) : en arrivant, il échange les couleurs des deux quarks (baryon), ou fait tourner la couleur du
     * quark et de son antiquark (méson). Les couleurs changent sans cesse, le total reste blanc : c'est la neutralité qui compte.
     * (Seulement quand l'assemblage est blanc et qu'on ne tire pas une corde.)
     */
    private void drawGluons(List<double[]> at, double[] junction, double alpha) {
        int n = at.size();
        boolean active = (n == 2 || n == 3) && white(mix(slots)) && stretching < 0 && shown.length == n;
        if (!active) return;
        double period = 1.3, p = (clock - pulseStart) / period;
        if (p >= 1) {
            if (n == 3) {
                int keep = shown[pulseFrom];
                shown[pulseFrom] = shown[pulseTo];
                shown[pulseTo] = keep;
                pulseFrom = rng.nextInt(3);
                pulseTo = (pulseFrom + 1 + rng.nextInt(2)) % 3;
            } else {
                int next = (shown[0] + 1 + rng.nextInt(2)) % 3;
                shown[0] = next;
                shown[1] = next;
                pulseFrom = 0;
                pulseTo = 1;
            }
            pulseStart = clock;
            p = 0;
        }
        int from = Math.min(pulseFrom, n - 1), to = Math.min(pulseTo, n - 1);
        double[] where = n == 3 ? (p < .5 ? SubRender.lerp(at.get(from), junction, 2 * p) : SubRender.lerp(junction, at.get(to), 2 * p - 1))
                : SubRender.lerp(at.get(0), at.get(1), p);
        int a = shown[from], b = n == 3 ? shown[to] : (shown[0] + 1) % 3;
        Color ca = Color.color(a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0), cb = Color.color(b == 0 ? 0 : 1, b == 1 ? 0 : 1, b == 2 ? 0 : 1);
        render.glow(where, .16, ca, .9 * alpha);
        render.glow(SubRender.add(where, new double[]{.05, .05, 0}), .1, cb, .8 * alpha);
        render.tag(where, .1, "gluon", COLOUR_NAMES[a] + "–anti" + COLOUR_NAMES[b] + I18n.text(" : il échange les couleurs"), ca.interpolate(Color.WHITE, .4),
                .55 * alpha * Math.sin(Math.PI * Math.min(1, p)));
    }

    /**
     * La mer : dans le sac, le vide n'est pas vide — des paires quark–antiquark et des gluons virtuels y naissent et y meurent sans
     * cesse. Des points fugaces (une paire : deux points de couleurs complémentaires ; un gluon : un point doré). C'est une
     * illustration : les vraies densités, qui dépendent de l'échelle à laquelle on regarde, sont dans l'onglet « mer de partons ».
     */
    private void drawSea(double radiusFm, double alpha) {
        if (alpha <= .01) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        for (int k = 0; k < 48; k++) {
            double cycle = clock * (2.4 + (k % 5) * .5) + k * .618;
            long gen = (long) Math.floor(cycle);
            double life = cycle - gen;
            java.util.Random r = new java.util.Random(gen * 7919L + k * 104729L);
            double x, y, z;
            do {
                x = 2 * r.nextDouble() - 1;
                y = 2 * r.nextDouble() - 1;
                z = 2 * r.nextDouble() - 1;
            } while (x * x + y * y + z * z > 1);
            double[] p = {x * radiusFm, y * radiusFm, z * radiusFm};
            double[] sp = render.project(p);
            if (sp == null) continue;
            double a = Math.sin(Math.PI * life) * alpha * .8;
            int c = r.nextInt(3);
            if (r.nextInt(3) == 0) {
                g.setFill(DocStyle.GOLD.deriveColor(0, 1, 1, a));
                g.fillOval(sp[0] - 1.3, sp[1] - 1.3, 2.6, 2.6);
            } else {
                double d = .06 * sp[3];
                g.setFill(Color.color(c == 0 ? 1 : .3, c == 1 ? 1 : .3, c == 2 ? 1 : .3, a));
                g.fillOval(sp[0] - d - 1.1, sp[1] - 1.1, 2.2, 2.2);
                g.setFill(Color.color(c == 0 ? .3 : 1, c == 1 ? .3 : 1, c == 2 ? .3 : 1, a));
                g.fillOval(sp[0] + d - 1.1, sp[1] - 1.1, 2.2, 2.2);
            }
        }
    }

    /**
     * L'anneau gyroscopique d'un spin : un anneau autour de l'axe du spin (ici vertical) et deux repères qui tournent — dans le sens
     * trigonométrique vu d'en haut pour +½ (↑), dans l'autre pour −½ (↓). Sans verdict, le sens n'est pas encore connu : l'anneau
     * est là, immobile.
     */
    private void spinRing(double[] centre, double radiusFm, int sense, Color c, double alpha) {
        if (alpha <= .01) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        double[] xs = new double[41], ys = new double[41];
        int m = 0;
        for (int k = 0; k <= 40; k++) {
            double phi = 2 * Math.PI * k / 40;
            double[] sp = render.project(new double[]{centre[0] + radiusFm * Math.cos(phi), centre[1], centre[2] + radiusFm * Math.sin(phi)});
            if (sp == null) continue;
            xs[m] = sp[0];
            ys[m] = sp[1];
            m++;
        }
        g.setStroke(c.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, (sense == 0 ? .3 : .6) * alpha));
        g.setLineWidth(1.3);
        if (m > 1) g.strokePolyline(xs, ys, m);
        if (sense == 0) return;
        for (int k = 0; k < 2; k++) {
            double phi = sense * clock * 3.2 + k * Math.PI;
            double[] sp = render.project(new double[]{centre[0] + radiusFm * Math.cos(phi), centre[1], centre[2] + radiusFm * Math.sin(phi)});
            if (sp == null) continue;
            g.setFill(Color.WHITE.deriveColor(0, 1, 1, .95 * alpha));
            g.fillOval(sp[0] - 2.8, sp[1] - 2.8, 5.6, 5.6);
        }
    }

    /** Les mésons arrachés par une corde qui casse : ils s'éloignent et s'effacent. */
    private void drawFlights() {
        flights.removeIf(f -> clock - f.t0() > 3);
        for (Flight f : flights) {
            double t = clock - f.t0(), fade = Math.max(0, 1 - t / 3);
            double[] centre = SubRender.add(f.start(), SubRender.scale(f.dir(), 1.1 * t));
            double[] a = SubRender.add(centre, SubRender.scale(f.dir(), -.2)), b = SubRender.add(centre, SubRender.scale(f.dir(), .2));
            render.glow(centre, .55, Color.web("#5EEAD4"), .35 * fade);
            render.fluxTube(a, b, .2, 1, fade);
            Slot q = f.content().get(0), p = f.content().get(1);
            render.quark(b, .2, DocStyle.colour(q.colour(), q.anti()), q.id() + (q.anti() ? "̄" : ""), fade);
            render.quark(a, .2, DocStyle.colour(p.colour(), p.anti()), p.id() + (p.anti() ? "̄" : ""), fade);
            render.tag(centre, .3, f.name(), "arraché par la corde : un méson blanc", Color.web("#5EEAD4"), fade);
        }
    }

    /**
     * La masse : les quarks u et d ne pèsent que quelques MeV (leur masse vient du Higgs), le proton 938. Au verdict, le compteur part
     * de la somme des masses des quarks et monte jusqu'à la masse du hadron : le reste, c'est l'énergie de l'interaction forte —
     * le mouvement des quarks confinés et le champ des gluons —, devenue masse (E = mc²). Pour le nucléon, la décomposition calculée
     * en QCD sur réseau (χQCD, Yang et al. 2018).
     */
    private void drawMass(GraphicsContext g, double x, double y, double w) {
        double current = 0;
        int quarks = 0;
        for (Slot sl : slots) {
            SmParticle p = table.particle(sl.id());
            if (p != null && isQuark(sl.id())) {
                current += p.getMassMev();
                quarks++;
            }
        }
        if (quarks == 0) return;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.5));
        g.setFill(DocStyle.PALE);
        I18n.fillText(g, DocStyle.caps("la masse"), x, y);
        boolean known = resultMatches() && result.getValid() && result.getMassMev() > 0;
        g.setFont(Font.font("Segoe UI", 10.5));
        if (!known) {
            g.setFill(DocStyle.WHITE);
            I18n.fillText(g, String.format(I18n.locale(), I18n.text("masses des quarks (Higgs) : Σ m = %s MeV"), DocStyle.number(current)), x, y + 18);
            g.setFill(DocStyle.DIM);
            I18n.fillText(g, "teste : l'interaction forte donne le reste", x, y + 34);
            return;
        }
        double big = result.getMassMev();
        double f = Math.max(0, Math.min(1, (clock - resultClock - .5) / 2.2));
        f = f * f * (3 - 2 * f);
        double shownMass = current + (big - current) * f;
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        g.setFill(DocStyle.WHITE);
        I18n.fillText(g, DocStyle.number(shownMass) + " MeV", x, y + 28);
        double bar = w, quarkPart = Math.min(1, current / big);
        g.setFill(Color.color(1, 1, 1, .08));
        g.fillRoundRect(x, y + 38, bar, 8, 4, 4);
        g.setFill(Color.web("#C4B5FD"));
        g.fillRoundRect(x, y + 38, Math.max(2, bar * quarkPart), 8, 4, 4);
        g.setFill(DocStyle.GOLD);
        g.fillRoundRect(x + bar * quarkPart, y + 38, bar * (1 - quarkPart) * f, 8, 4, 4);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(Color.web("#C4B5FD"));
        I18n.fillText(g, String.format(I18n.locale(), "quarks (Higgs) : %s MeV · %.0f %%", DocStyle.number(current), 100 * quarkPart), x, y + 60);
        g.setFill(DocStyle.GOLD.deriveColor(0, 1, 1, Math.max(.3, f)));
        I18n.fillText(g, String.format(I18n.locale(), "interaction forte : %s MeV · %.0f %%", DocStyle.number(big - current), 100 * (1 - quarkPart)), x, y + 74);
        g.setFill(DocStyle.DIM);
        wrap(g, "le mouvement des quarks confinés et le champ des gluons, devenus masse (E = mc²)", x, y + 89, w, 13, Font.font("Segoe UI", 9.5));
        String content = result.getContent();
        if (content.equals("uud") || content.equals("udd") || content.equals("duu") || content.equals("ddu"))
            wrap(g, "réseau (χQCD 2018) : énergie des quarks 32 %, champ des gluons 36 %, masse des quarks 9 % (mer comprise), anomalie de trace 23 %",
                    x, y + 118, w, 13, Font.font("Segoe UI", 9.5));
    }

    /** Une flèche de spin à côté d'un quark : ↑ pour +½, ↓ pour −½. */
    private static void arrow(GraphicsContext g, double x, double y, boolean up, double alpha) {
        double s = up ? -1 : 1;
        g.setStroke(Color.WHITE.deriveColor(0, 1, 1, .9 * alpha));
        g.setLineWidth(1.8);
        g.strokeLine(x, y - 9 * s, x, y + 9 * s);
        g.strokeLine(x, y + 9 * s, x - 4, y + 4 * s);
        g.strokeLine(x, y + 9 * s, x + 4, y + 4 * s);
    }

    /**
     * Les spins des quarks dans l'état choisi, |J, M = J⟩ sans moment orbital : J = 3/2 → ↑↑↑ ; J = ½ → ↑↑↓ ; J = 1 → ↑↑ ;
     * J = 0 → ↑↓ (le nombre de ↓ est (n − 2J)/2). C'est une configuration de l'état, pas la seule (l'état réel en superpose
     * plusieurs) ; leurs projections s'additionnent bien à M = J. Null tant qu'il n'y a pas de verdict.
     */
    private int[] spinArrows() {
        if (!resultMatches() || !result.getValid() || result.getStatesCount() == 0) return null;
        int chosen = Math.max(0, Math.min(result.getStatesCount() - 1, result.getChosenState()));
        double j = result.getStates(chosen).getSpin();
        int n = slots.size(), downs = (int) Math.round((n - 2 * j) / 2);
        if (downs < 0 || downs > n) return null;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = i >= n - downs ? -1 : 1;
        return out;
    }

    /**
     * L'arithmétique, sous le sac : la charge, le nombre baryonique et le spin s'écrivent comme des additions dont chaque terme est
     * une pastille partie de son quark — au test, elles s'envolent des quarks vers la ligne, puis le total apparaît.
     */
    private void drawArithmetic(GraphicsContext g, double left, double right) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) if (isQuark(slots.get(i).id())) order.add(i);
        if (order.isEmpty()) return;
        order.sort((a, b) -> Integer.compare(slots.get(a).place(), slots.get(b).place()));
        boolean animate = resultMatches();
        double since = clock - resultClock;
        double y0 = benchY + benchR + 26;
        double charge = 0, baryons = 0;
        List<String[]> chargeTerms = new ArrayList<>(), baryonTerms = new ArrayList<>(), spinTerms = new ArrayList<>();
        int[] spins = spinArrows();
        for (int k = 0; k < order.size(); k++) {
            Slot s = slots.get(order.get(k));
            SmParticle p = table.particle(s.id());
            double q = p == null ? 0 : (s.anti() ? -p.getCharge() : p.getCharge());
            charge += q;
            baryons += s.anti() ? -1 / 3.0 : 1 / 3.0;
            chargeTerms.add(new String[]{SmTable.charge(q)});
            baryonTerms.add(new String[]{s.anti() ? "−⅓" : "+⅓"});
            if (spins != null) spinTerms.add(new String[]{spins[order.get(k)] > 0 ? "↑ +½" : "↓ −½"});
        }
        String spinTotal = spins == null ? null : "J = " + spinText(result.getStates(Math.max(0, Math.min(result.getStatesCount() - 1,
                result.getChosenState()))).getSpin());
        String[][] rows = {{"charge", SmTable.charge(charge) + " e"}, {"nombre baryonique", baryonText(baryons)}, {"spin", spinTotal}};
        List<List<String[]>> terms = List.of(chargeTerms, baryonTerms, spinTerms);
        double chip = 46, gapX = 18;
        for (int r = 0; r < 3; r++) {
            if (rows[r][1] == null) continue;
            double y = y0 + r * 30;
            List<String[]> t = terms.get(r);
            double total = t.size() * chip + (t.size() - 1) * gapX + 130;
            double x0 = Math.max(left + 8, benchX - total / 2);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setFill(DocStyle.PALE);
            I18n.fillText(g, rows[r][0], x0 - 8, y + 4);
            double allIn = 0;
            for (int k = 0; k < t.size(); k++) {
                Slot s = slots.get(order.get(k));
                double ex = x0 + k * (chip + gapX), ey = y;
                // au test, la pastille part de son quark
                double f = animate ? Math.max(0, Math.min(1, (since - .25 - .14 * k - .35 * r) / .45)) : 1;
                f = f * f * (3 - 2 * f);
                allIn = Math.max(allIn, .25 + .14 * k + .35 * r + .45);
                double[] from = screen.size() > order.get(k) && screen.get(order.get(k)) != null ? screen.get(order.get(k)) : new double[]{ex, ey};
                double cx = from[0] + (ex + chip / 2 - from[0]) * f, cy = from[1] + (ey - from[1]) * f;
                Color col = DocStyle.colour(s.colour(), s.anti());
                g.setFill(col.deriveColor(0, 1, .45, .85));
                g.fillRoundRect(cx - chip / 2, cy - 10, chip, 20, 20, 20);
                g.setStroke(col.deriveColor(0, 1, 1, .9));
                g.setLineWidth(1);
                g.strokeRoundRect(cx - chip / 2, cy - 10, chip, 20, 20, 20);
                g.setTextAlign(TextAlignment.CENTER);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
                g.setFill(Color.WHITE);
                I18n.fillText(g, t.get(k)[0], cx, cy + 4);
                if (k > 0 && f > .9) {
                    g.setFill(DocStyle.PALE);
                    I18n.fillText(g, "+", ex - gapX / 2, ey + 4);
                }
            }
            double show = animate ? Math.max(0, Math.min(1, (since - allIn) / .3)) : 1;
            if (show > 0) {
                double ex = x0 + t.size() * (chip + gapX) - gapX + 10;
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12.5 + 3 * (1 - show)));
                g.setFill(DocStyle.WHITE.deriveColor(0, 1, 1, show));
                I18n.fillText(g, "=  " + rows[r][1], ex, y + 4.5);
            }
        }
        if (spins == null && !order.isEmpty()) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setFill(DocStyle.DIM);
            I18n.fillText(g, "spin : teste l'assemblage pour connaître ses états (J)", benchX, y0 + 64);
        } else if (spins != null) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(Font.font("Segoe UI", 9.5));
            g.setFill(DocStyle.DIM);
            I18n.fillText(g, "(une configuration de l'état |J, M = J⟩, sans moment orbital : les projections ±½ s'ajoutent)", benchX, y0 + 80);
        }
    }

    /** Le nombre baryonique en tiers exacts : un hadron en a un entier (0, ±1…) ; sinon il manque des quarks. */
    static String baryonText(double b) {
        long thirds = Math.round(b * 3);
        if (thirds % 3 != 0) return (thirds < 0 ? "−" : "") + Math.abs(thirds) + I18n.text("/3 — pas un entier : il manque des quarks");
        long whole = thirds / 3;
        return whole == 0 ? "0 (méson)" : whole == 1 ? "1 (baryon)" : whole == -1 ? "−1 (antibaryon)" : String.valueOf(whole).replace('-', '−');
    }

    static String spinText(double j) {
        if (Math.abs(j - .5) < .01) return "½";
        if (Math.abs(j - 1.5) < .01) return "3/2";
        if (Math.abs(j - 2.5) < .01) return "5/2";
        return String.format(I18n.locale(), "%.0f", j);
    }

    /** La particule repoussée revient à sa case, et la raison s'affiche. */
    private void drawBounce(GraphicsContext g) {
        double t = clock - bounceStart;
        if (bounceId != null && t < .7) {
            double[] home = table.centre(bounceId);
            SmParticle p = table.particle(bounceId);
            if (home != null && p != null) {
                double f = Math.min(1, t / .6);
                double e = 1 - Math.pow(1 - f, 3);
                double x = bounceX + (home[0] - bounceX) * e, y = bounceY + (home[1] - bounceY) * e - 60 * Math.sin(Math.PI * f);
                DocStyle.blob(g, x, y, 16, DocStyle.BAD, .9 * (1 - f * .5));
                g.setTextAlign(TextAlignment.CENTER);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
                g.setFill(Color.WHITE);
                I18n.fillText(g, p.getSymbol(), x, y + 5);
            }
        }
        double age = clock - noticeStart;
        if (!notice.isEmpty() && age >= 0 && age < 6) {
            double a = Math.max(0, Math.min(1, Math.min(age * 4, 6 - age)));
            double w = Math.min(560, benchR * 4.2), x = benchX - w / 2, y = benchY + benchR + 120;
            Font font = Font.font("Segoe UI", 11.5);
            g.setFill(noticeOk ? Color.color(.03, .1, .06, .85 * a) : Color.color(.12, .03, .04, .85 * a));
            double hText = 16 * Math.max(1, Math.ceil(ReactorView.width(notice, font) / (w - 24)));
            g.fillRoundRect(x, y, w, hText + 14, 10, 10);
            g.setStroke((noticeOk ? DocStyle.GOOD : DocStyle.BAD).deriveColor(0, 1, 1, .8 * a));
            g.setLineWidth(1);
            g.strokeRoundRect(x + .5, y + .5, w - 1, hText + 13, 10, 10);
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(DocStyle.WHITE.deriveColor(0, 1, 1, a));
            wrap(g, notice, x + 12, y + 18, w - 24, 16, font);
        }
    }

    /**
     * Les produits de la désintégration à l'instant `phase` (0 → 1) : nom, contenu, position (fm). Chacun part dans la direction de
     * son impulsion calculée, à une distance proportionnelle à sa vitesse v = p/E — le film est ralenti, mais les directions et
     * les rapports de vitesses sont ceux du calcul. Les produits du W partent du point où le W se matérialise.
     */
    private List<Object[]> products(double phase) {
        List<Object[]> out = new ArrayList<>();
        double reach = 1.7, travel = Math.min(1, phase * 1.15);
        for (HadronProduct p : result.getProductsList()) {
            double[] dir = {p.getMomentumMev(0), p.getMomentumMev(1), p.getMomentumMev(2)};
            double pm = SubRender.norm(dir), beta = pm / Math.max(1e-9, p.getEnergyMev());
            double[] u = pm > 0 ? SubRender.scale(dir, 1 / pm) : new double[]{1, 0, 0};
            double[] start = p.getFromW() ? SubRender.scale(u, .5 * Math.min(1, travel * 3)) : new double[]{0, 0, 0};
            double[] where = SubRender.add(start, SubRender.scale(u, reach * beta * travel));
            out.add(new Object[]{p.getName(), p.getContent(), where, p, start});
        }
        return out;
    }

    /** La désintégration tirée : les produits (sacs, leptons, photons, W) à leur place, avec leur étiquette. */
    private void drawDecay(GraphicsContext g, double left, double right, double h) {
        double f = decayPhase();
        if (f < 0) return;
        String kind = result.getFateKind();
        boolean weak = kind.startsWith("weak") || kind.equals("top");
        render.flash(new double[]{0, 0, 0}, .3 + .9 * Math.min(1, f * 4), weak ? DocStyle.WAVE : DocStyle.GOLD, Math.max(0, 1 - f * 3));
        boolean wDrawn = false;
        for (Object[] pr : products(f)) {
            String name = (String) pr[0], content = (String) pr[1];
            double[] where = (double[]) pr[2], start = (double[]) pr[4];
            HadronProduct p = (HadronProduct) pr[3];
            double pm = Math.sqrt(p.getMomentumMev(0) * p.getMomentumMev(0) + p.getMomentumMev(1) * p.getMomentumMev(1)
                    + p.getMomentumMev(2) * p.getMomentumMev(2));
            double beta = pm / Math.max(1e-9, p.getEnergyMev());
            if (p.getFromW() && !wDrawn && !name.startsWith("W")) {
                render.wave(new double[]{0, 0, 0}, start, .25, .07, clock * 8, 1, DocStyle.WAVE, Math.max(.3, 1 - f));
                render.tag(start, .1, "W virtuel", "change la saveur (interaction faible)", DocStyle.WAVE, Math.max(.3, 1 - f));
                wDrawn = true;
            }
            boolean lepton = name.matches("e[+-]|μ[+-]|τ[+-]|ν.*");
            if (lepton) {
                // le neutrino : presque rien, il n'interagit que par la force faible
                Color c = name.startsWith("ν") ? Color.web("#CFE8FF") : DocStyle.ELECTRON;
                render.trail(List.of(start, SubRender.lerp(start, where, .5), where), c, name.startsWith("ν") ? .35 : 1);
                render.lepton(where, c, name.startsWith("ν") ? .4 : 1);
            } else if (name.equals("γ")) {
                render.wave(new double[]{0, 0, 0}, where, .3, .05, clock * 8, 1, DocStyle.WAVE, 1);
            } else if (name.startsWith("W")) {
                render.wave(new double[]{0, 0, 0}, where, .4, .12, clock * 6, 1, DocStyle.WAVE, 1);
            } else if (!content.isEmpty()) {
                List<double[]> qs = layout(content, where, .7);
                int[] colours = neutral(content);
                double[] j = where;
                for (int i = 0; i < qs.size(); i++) {
                    if (content.length() >= 3) render.spring(j, qs.get(i), Math.min(1, f * 3), i * 3);
                }
                if (content.length() == 2) render.spring(qs.get(0), qs.get(1), Math.min(1, f * 3), 2);
                for (int i = 0; i < qs.size(); i++) {
                    char ch = content.charAt(i);
                    render.quark(qs.get(i), .2, DocStyle.colour(colours[i], Character.isUpperCase(ch)),
                            Character.toLowerCase(ch) + (Character.isUpperCase(ch) ? "̄" : ""), Math.min(1, f * 3));
                }
            } else {
                render.quark(where, .25, DocStyle.GOLD, name, 1);
            }
            render.tag(where, lepton ? .1 : .7, CollisionScene.pretty(name), String.format(I18n.locale(), "p %s MeV/c · v %.2f c",
                    DocStyle.number(pm), beta), lepton ? DocStyle.ELECTRON : DocStyle.RIM, Math.min(1, f * 4));
        }
        if (weak) DocStyle.label(g, kind.equals("top") ? "le top se désintègre par le W" : "interaction faible : un W virtuel change la saveur",
                benchX, h - 40, 11, TextAlignment.CENTER, Math.min(1, f * 3));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Font.font("Segoe UI", 10.5));
        g.setFill(DocStyle.PALE);
        I18n.fillText(g, "film ralenti : directions et rapports de vitesses calculés, distances à l'échelle de v = p/E", benchX, h - 22);
    }

    private void drawResult(GraphicsContext g, double x, double y, double w, double h) {
        DocStyle.panel(g, x, y, w, h);
        DocStyle.label(g, "le verdict du calcul", x + 18, y + 26, 11, TextAlignment.LEFT, 1);
        stateRows.clear();
        double cx = x + 18, cy = y + 52, inner = w - 36;
        if (result == null) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(DocStyle.PALE);
            wrap(g, I18n.text("Choisis un moule (baryon, méson ou libre), glisse des quarks dans ses emplacements, fais du blanc, puis « Tester la ")
                    + I18n.text("stabilité ». Les règles : la couleur doit être neutre (le mélange de couleurs est blanc : rouge + vert + bleu, ou ")
                    + I18n.text("couleur + anticouleur), deux quarks identiques ne peuvent pas porter la même couleur (Pauli), et le nombre de quarks ")
                    + I18n.text("moins celui d'antiquarks doit être un multiple de 3."), cx, cy, inner, 16, Font.font("Segoe UI", 11.5));
            massTop = y + 240;
            return;
        }
        massTop = y + h;
        boolean matches = resultMatches();
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        g.setFill(result.getValid() ? DocStyle.GOOD : DocStyle.BAD);
        I18n.fillText(g, result.getValid() ? "✓  assemblage viable" : "✕  assemblage refusé", cx, cy);
        cy += 20;
        if (!matches) {
            g.setFont(Font.font("Segoe UI", 10.5));
            g.setFill(DocStyle.DIM);
            I18n.fillText(g, "(résultat de l'assemblage précédent)", cx, cy);
            cy += 16;
        }
        g.setFill(DocStyle.WHITE);
        for (String reason : result.getReasonsList()) cy += wrap(g, "·  " + reason, cx, cy, inner, 15, Font.font("Segoe UI", 11)) + 4;
        if (!result.getReasonsList().isEmpty()) {
            massTop = cy;
            return;
        }
        cy += 4;
        g.setFont(Font.font("Segoe UI", 11));
        String[][] numbers = {{"contenu", pretty(result.getContent())}, {"charge", SmTable.charge(result.getCharge())},
                {"nombre baryonique", String.format(I18n.locale(), "%.0f", result.getBaryonNumber())},
                {"étrangeté · charme · beauté", result.getStrangeness() + " · " + result.getCharm() + " · " + result.getBeauty()}};
        for (String[] row : numbers) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setFill(DocStyle.PALE);
            I18n.fillText(g, row[0], cx, cy);
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(DocStyle.WHITE);
            I18n.fillText(g, row[1], cx + inner, cy);
            cy += 17;
        }
        if (result.getStatesCount() > 0) {
            cy += 6;
            DocStyle.label(g, "états de spin (clic pour choisir)", cx, cy, 9.5, TextAlignment.LEFT, .9);
            cy += 8;
            for (int i = 0; i < result.getStatesCount(); i++) {
                HadronSpinState s = result.getStates(i);
                boolean chosen = i == result.getChosenState();
                stateRows.add(new double[]{cx - 6, cy, inner + 12, 34});
                if (chosen) {
                    g.setFill(Color.color(.55, .85, 1, .14));
                    g.fillRoundRect(cx - 6, cy, inner + 12, 34, 8, 8);
                }
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11.5));
                g.setFill(DocStyle.WHITE);
                String spin = Math.abs(s.getSpin() - .5) < .01 ? "½" : Math.abs(s.getSpin() - 1.5) < .01 ? "3/2" : String.format(I18n.locale(), "%.0f", s.getSpin());
                I18n.fillText(g, "J = " + spin + (s.getKnownName().isEmpty() ? "  ·  inconnu du PDG" : "  ·  " + CollisionScene.pretty(s.getKnownName())), cx, cy + 14);
                g.setFont(Font.font("Segoe UI", 10.5));
                g.setFill(DocStyle.PALE);
                I18n.fillText(g, s.getKnownName().isEmpty()
                        ? String.format(I18n.locale(), I18n.text("modèle : %s MeV"), DocStyle.number(s.getModelMassMev()))
                        : String.format(I18n.locale(), I18n.text("modèle %s MeV  ·  mesuré %s MeV  ·  écart %+.0f"), DocStyle.number(s.getModelMassMev()),
                        DocStyle.number(s.getKnownMassMev()), s.getErrorMev()), cx, cy + 28);
                cy += 38;
            }
        }
        cy += 6;
        DocStyle.label(g, fateTitle(result.getFateKind()), cx, cy + 4, 12, TextAlignment.LEFT, 1);
        cy += 20;
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(DocStyle.WHITE);
        cy += wrap(g, result.getFateSummary(), cx, cy, inner, 15, Font.font("Segoe UI", 11)) + 4;
        if (result.getLifetimeS() > 0) {
            g.setFill(DocStyle.WAVE);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11.5));
            I18n.fillText(g, I18n.text("durée de vie τ = ") + DocStyle.number(result.getLifetimeS()) + " s", cx, cy + 4);
            cy += 20;
        }
        if (result.getChannelsCount() > 0) {
            for (int i = 0; i < Math.min(6, result.getChannelsCount()); i++) {
                HadronChannel c = result.getChannels(i);
                if (cy > y + h - 60) break;
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(Font.font("Segoe UI", 10.5));
                g.setFill(DocStyle.PALE);
                I18n.fillText(g, pretty(c.getLabel()), cx, cy + 4, inner * .72);
                if (c.getBranching() > 0) {
                    g.setFill(Color.color(1, 1, 1, .1));
                    g.fillRoundRect(cx + inner * .74, cy - 4, inner * .26, 7, 3, 3);
                    g.setFill(DocStyle.GOLD);
                    g.fillRoundRect(cx + inner * .74, cy - 4, inner * .26 * Math.min(1, c.getBranching()), 7, 3, 3);
                }
                cy += 16;
            }
        }
        if (!result.getDecayLabel().isEmpty() && cy < y + h - 30) {
            cy += 6;
            g.setFill(DocStyle.WHITE);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            cy += wrap(g, I18n.text("Tirage montré : ") + pretty(result.getDecayLabel()), cx, cy + 4, inner, 15, Font.font("Segoe UI", FontWeight.BOLD, 11));
        }
        massTop = cy;
    }

    static String fateTitle(String kind) {
        return switch (kind) {
            case "stable" -> "stable";
            case "strong" -> "désintégration forte";
            case "electromagnetic" -> "désintégration électromagnétique";
            case "weak", "weak-heavy" -> "désintégration faible";
            case "top" -> "le top ne forme pas de hadron";
            case "fall-apart" -> "état exotique";
            case "refused" -> "refusé";
            default -> "destin inconnu";
        };
    }

    /** Contenu : minuscules = quarks, majuscules = antiquarks → u d̄… ; et les noms de la table en exposants. */
    static String pretty(String content) {
        StringBuilder out = new StringBuilder();
        boolean letters = content.chars().allMatch(Character::isLetter) && content.length() <= 5 && content.chars().allMatch(ch -> "udscbtUDSCBT".indexOf(ch) >= 0);
        if (letters) {
            for (char ch : content.toCharArray()) out.append(Character.toLowerCase(ch)).append(Character.isUpperCase(ch) ? "̄" : "");
            return out.toString();
        }
        // une charge collée à un nom (π+, e-) devient un exposant ; le « + » entouré d'espaces reste une somme
        return content.replaceAll("(?<=\\S)\\+\\+", "⁺⁺").replaceAll("(?<=\\S)\\+", "⁺").replaceAll("(?<=\\S)-(?=\\s|$)", "⁻")
                .replaceAll("(?<=[A-Za-zΣΞΛΩΔπηφρω])0(?=\\s|$)", "⁰");
    }

    private static double wrap(GraphicsContext g, String text, double x, double y, double w, double lead, Font font) {
        g.setFont(font);
        List<String> rows = new ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (ReactorView.width(next, font) > w && !line.isEmpty()) {
                rows.add(line);
                line = word;
            } else line = next;
        }
        if (!line.isEmpty()) rows.add(line);
        for (int i = 0; i < rows.size(); i++) I18n.fillText(g, rows.get(i), x, y + i * lead);
        return rows.size() * lead;
    }
}
