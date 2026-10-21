package org.genesis.fusion;

import org.genesis.app.I18n;
import com.studio.genesis.grpc.CollisionBody;
import com.studio.genesis.grpc.CollisionNucleus;
import com.studio.genesis.grpc.CollisionRun;
import com.studio.genesis.grpc.CollisionSample;
import javafx.scene.paint.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import org.genesis.fusion.FusionField.Body;
import org.genesis.fusion.FusionField.V;
import static org.genesis.fusion.FusionField.C;
import static org.genesis.fusion.ReactorView.duration;
import static org.genesis.fusion.ReactorView.energy;
import static org.genesis.fusion.ReactorView.fmt;
import static org.genesis.fusion.ReactorView.lengthText;
import static org.genesis.fusion.ReactorView.force;
import static org.genesis.fusion.ReactorView.probability;
import static org.genesis.fusion.ReactorView.scientificShort;
import static org.genesis.fusion.ReactorView.spinText;

/**
 * Les calculs montrés pendant la collision : le tableau de bord (ses jauges, étape par étape), et les équations
 * du mode Inspecter (la loi de l'instant, le champ en un point : E₁, E₂, leur somme, ε₀E²/2, φ…). Tout part des
 * échantillons du moteur et de ce que le rendu a dessiné à cette image (mêmes positions, même échelle).
 */
public final class FusionReadouts {
    /** e²/4πε₀ (MeV·fm) ; 1 MeV/fm en newtons ; ħc (MeV·fm) ; constante de structure fine. */
    static final double KE = 1.43996, MEV_FM_N = 160.2176634, HBARC = 197.3269804, ALPHA = 1 / 137.035999;
    /** 1 MeV en joules ; 1 MeV en kelvins ; pression au cœur du Soleil (Pa) ; 1 MeV/fm³ en pascals ; cœur du Soleil (K). */
    static final double MEV_J = 1.602176634e-13, MEV_K = 1.160451812e10, SUN_CORE_PA = 2.5e16, MEV_FM3_PA = 1.602176634e32, SUN_CORE_K = 1.57e7;
    /** k·e/r² en V/m et k·e/r en V, pour r en fm ; ε₀ ; charge élémentaire. */
    static final double FIELD = 1.43996e21, POTENTIAL = 1.43996e6, EPS0 = 8.8541878128e-12, ELEMENTARY = 1.602176634e-19;
    private static final Color CYAN = Color.web("#36CDF2"), AMBER = Color.web("#FFB24A"), HOT = Color.web("#FF7A45");

    /** La sonde : la zone touchée, ce qu'elle est, les calculs ; les vecteurs E (dx, dy à l'écran, couleur r, g, b). */
    record Card(String zone, String about, List<String> lines, Color accent, List<double[]> vectors) {}

    private final BiFunction<Integer, Integer, String> symbol;
    private CollisionRun prepared;
    private double rStart, rMin, v0, fContact, eStar0;
    private boolean touched;
    private int z1, z2, a1, a2;
    private String s1 = "A", s2 = "B";

    /** `symbol` : (Z, A) → le nom à afficher (²H, ⁴He, n…). */
    FusionReadouts(BiFunction<Integer, Integer, String> symbol) {
        this.symbol = symbol;
    }

    // ================================================================== la physique d'un instant
    /** La température d'un noyau chaud (gaz de Fermi, a = A/8 MeV⁻¹) : T = √(8E* / A), en MeV. */
    static double temperature(double excitation, int a) {
        return Math.sqrt(8 * Math.max(0, excitation) / Math.max(1, a));
    }

    /** La pression thermique d'un noyau chaud : P = ⅔·E* / V, V = 4/3·π·(1,2·A^⅓)³ fm³ — en pascals. */
    static double pressure(double excitation, int a) {
        double radius = 1.2 * Math.cbrt(Math.max(1, a)), volume = 4 / 3.0 * Math.PI * radius * radius * radius;
        return 2 / 3.0 * Math.max(0, excitation) / volume * MEV_FM3_PA;
    }

    /** Le paramètre de Sommerfeld η = Z₁Z₂α/β pour une énergie E (MeV) dans le centre de masse, μ en MeV. */
    static double sommerfeld(int za, int zb, double e, double mu) {
        return za * zb * ALPHA / Math.sqrt(2 * Math.max(e, 1e-12) / Math.max(mu, 1));
    }

    private void prepare(CollisionRun run) {
        if (run == prepared) return;
        prepared = run;
        CollisionSample first = run.getSamples(0);
        CollisionBody b1 = first.getBodies(0), b2 = first.getBodies(Math.min(1, first.getBodiesCount() - 1));
        z1 = b1.getZ();
        z2 = b2.getZ();
        a1 = b1.getA();
        a2 = b2.getA();
        s1 = symbol.apply(z1, a1);
        s2 = symbol.apply(z2, a2);
        rStart = separation(first);
        rMin = rStart;
        touched = false;
        for (CollisionSample s : run.getSamplesList())
            if (s.getPhase().equals("contact")) touched = true;
        for (CollisionSample s : run.getSamplesList())
            if (s.getBodiesCount() == 2 && (s.getPhase().equals("approach") || s.getPhase().equals("scattered"))) rMin = Math.min(rMin, separation(s));
        v0 = first.getBodiesCount() == 2 ? relative(first) : 0;
        double contact = Math.max(1, run.getContactFm());
        fContact = KE * z1 * z2 / (contact * contact) * MEV_FM_N;
        eStar0 = run.getExcitationMev();
    }

    private static double separation(CollisionSample s) {
        if (s.getBodiesCount() < 2) return 0;
        CollisionBody a = s.getBodies(0), b = s.getBodies(1);
        double dx = a.getPosition(0) - b.getPosition(0), dy = a.getPosition(1) - b.getPosition(1), dz = a.getPosition(2) - b.getPosition(2);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double relative(CollisionSample s) {
        CollisionBody a = s.getBodies(0), b = s.getBodies(1);
        if (a.getVelocityCount() < 3 || b.getVelocityCount() < 3) return 0;
        double dx = a.getVelocity(0) - b.getVelocity(0), dy = a.getVelocity(1) - b.getVelocity(1), dz = a.getVelocity(2) - b.getVelocity(2);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) / C;
    }

    /** Le dernier échantillon calculé avant l'instant `time`. */
    private static CollisionSample sampleAt(CollisionRun run, double time) {
        CollisionSample out = run.getSamples(0);
        for (CollisionSample s : run.getSamplesList()) {
            if (s.getTimeS() > time) break;
            out = s;
        }
        return out;
    }

    // ================================================================== le tableau de bord
    /** Avant le tir : ce que promet le couple choisi à l'énergie réglée. */
    FusionDashboard.Board ready(CollisionNucleus a, CollisionNucleus b, double energyKev, double barrierKev) {
        if (a == null || b == null || energyKev <= 0) return null;
        double e = energyKev / 1000, barrier = barrierKev / 1000;
        double ma = a.getMassMev() > 0 ? a.getMassMev() : a.getA() * 931.494, mb = b.getMassMev() > 0 ? b.getMassMev() : b.getA() * 931.494;
        double mu = ma * mb / (ma + mb), eta = sommerfeld(a.getZ(), b.getZ(), e, mu), gamow = Math.exp(-2 * Math.PI * eta);
        double lambda = 2 * Math.PI * HBARC / Math.sqrt(2 * mu * e);
        List<FusionDashboard.Dial> dials = List.of(
                new FusionDashboard.Dial("eb", "énergie / mur", barrier > 0 ? fmt(e / barrier) : "—", "E / B", barrier > 0 ? e / barrier : 0),
                new FusionDashboard.Dial("eta", "Sommerfeld", fmt(eta), "η", Math.log10(1 + eta) / 2),
                new FusionDashboard.Dial("gamow", "facteur Gamow", scientificShort(gamow), "e^(−2πη)", 1 + Math.log10(Math.max(gamow, 1e-300)) / 20));
        List<String> formulas = new ArrayList<>();
        if (barrier > 0) formulas.add("mur de Coulomb B = " + energy(barrierKev) + " · E = " + energy(energyKev));
        formulas.add("η = Z₁Z₂α/β = " + fmt(eta) + (eta < 1 ? " : une onde" : eta > 10 ? " : une bille" : ""));
        formulas.add("λ = h/p = " + fmt(lambda) + " fm · μ = " + fmt(mu) + " MeV/c²");
        return new FusionDashboard.Board("PRÊT", null, dials, List.of(), formulas);
    }

    /** L'instant montré, selon l'étape. */
    FusionDashboard.Board board(CollisionRun run, CollisionTimeline time, FusionRenderer.Report r) {
        prepare(run);
        FusionDashboard.Board b = switch (r.stage) {
            case "approach", "scattered" -> approach(run, r);
            case "tunnel" -> tunnel(run);
            case "merge", "contact" -> time.computedMerge() || r.stage.equals("contact") ? merge(run, r) : melt(run, r);
            case "relax" -> melt(run, r);
            case "compound", "evaporation" -> hot(run, r);
            case "products" -> products(run);
            default -> null;
        };
        if (b == null) return null;
        // Ce que chaque étape ajoute : la courbe du potentiel tant que les noyaux sont séparés ou se touchent, les
        // équivalences d'énergie quand elle est libérée.
        FusionDashboard.Plot plot = switch (r.stage) {
            case "approach", "scattered", "tunnel", "merge", "contact" -> plot(run, r);
            default -> null;
        };
        List<String[]> equivalents = r.stage.equals("products") ? equivalents(run) : List.of();
        return new FusionDashboard.Board(b.stage(), b.split(), b.dials(), b.bars(), b.formulas(), plot, equivalents);
    }

    // ================================================================== la courbe du potentiel
    /**
     * La courbe V(r) du couple (Coulomb + Woods–Saxon, calculée par le moteur), de 0,6 fois le contact à 3,6 fois le
     * rebroussement : la barrière, le puits nucléaire (écrêté), l'énergie du tir en travers, et où sont les noyaux.
     * Sous la barrière, l'énergie coupe la courbe au rebroussement ; au-dessus, elle passe au-dessus du sommet.
     */
    private FusionDashboard.Plot plot(CollisionRun run, FusionRenderer.Report r) {
        int n = Math.min(run.getRadialFmCount(), run.getPotentialMevCount());
        if (n < 8) return null;
        double contact = Math.max(1, run.getContactFm()), e = run.getEnergyKev() / 1000;
        double turn = Math.max(contact, KE * z1 * z2 / Math.max(e, 1e-9));
        double lo = Math.max(run.getRadialFm(0), contact * .6), hi = Math.min(run.getRadialFm(n - 1), 3.6 * turn);
        if (hi <= lo * 1.5) return null;
        int points = 120;
        double[] xs = new double[points], ys = new double[points];
        double peak = 0;
        for (int i = 0; i < points; i++) {
            xs[i] = lo + (hi - lo) * i / (points - 1.0);
            ys[i] = potentialAt(run, n, xs[i]);
            peak = Math.max(peak, ys[i]);
        }
        double top = 1.3 * Math.max(e, peak);
        double marker = r.stage.equals("tunnel") ? turn : r.separation > 0 ? r.separation : turn;
        marker = Math.max(lo, Math.min(hi, marker));
        return new FusionDashboard.Plot("POTENTIEL V(r)", xs, ys, e, "E " + energy(run.getEnergyKev()), marker,
                potentialAt(run, n, marker), lo, hi, -.28 * top, top, "fm", "MeV");
    }

    /** V(r) interpolé dans le profil du moteur. */
    private static double potentialAt(CollisionRun run, int n, double r) {
        if (r <= run.getRadialFm(0)) return run.getPotentialMev(0);
        for (int i = 1; i < n; i++) {
            if (run.getRadialFm(i) >= r) {
                double f = (r - run.getRadialFm(i - 1)) / Math.max(1e-12, run.getRadialFm(i) - run.getRadialFm(i - 1));
                return run.getPotentialMev(i - 1) + f * (run.getPotentialMev(i) - run.getPotentialMev(i - 1));
            }
        }
        return run.getPotentialMev(n - 1);
    }

    // ================================================================== les équivalences
    /**
     * Ce que vaut l'énergie libérée, pour qu'on la situe : par réaction, puis pour un gramme de combustible — en joules,
     * en TNT, en bombes d'Hiroshima, en consommation d'une ville. Rien si la réaction absorbe de l'énergie.
     */
    private List<String[]> equivalents(CollisionRun run) {
        double q = run.getQMev();
        if (q <= 0 || run.getSamplesCount() == 0) return List.of();
        double fuelMev = 0;
        for (CollisionBody b : run.getSamples(0).getBodiesList()) fuelMev += b.getMassMev() > 0 ? b.getMassMev() : b.getA() * 931.494;
        double perReaction = q * MEV_J, perGram = fuelMev > 0 ? perReaction / (fuelMev / ResultView.U_MEV * ResultView.U_KG) / 1000 : 0;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"1 réaction", scientificShort(perReaction) + " J"});
        if (perGram > 0) {
            rows.add(new String[]{"1 g de combustible", scientificShort(perGram) + " J"});
            rows.add(new String[]{"soit en TNT", ResultView.tnt(perGram)});
            rows.add(new String[]{"soit en bombes d'Hiroshima", fmtRatio(perGram / 6.3e13)});
        }
        return rows;
    }

    private static String fmtRatio(double r) {
        return r >= 1e4 ? scientificShort(r) + " ×" : r >= 100 ? String.format(I18n.locale(), "%,.0f ×", r) : fmt(r) + " ×";
    }

    private FusionDashboard.Board approach(CollisionRun run, FusionRenderer.Report r) {
        double e = run.getEnergyKev() / 1000, k = Math.max(0, r.kinetic), v = Math.max(0, r.potential);
        var split = new FusionDashboard.Split("ÉNERGIE  E = K + V", energy((k + v) * 1000), List.of(
                new FusionDashboard.Part("élan K", energy(k * 1000), k, CYAN),
                new FusionDashboard.Part("tension V", energy(v * 1000), v, AMBER)));
        double sep = Math.max(r.separation, 1e-3), push = KE * z1 * z2 / (sep * sep) * MEV_FM_N, coulomb = KE * z1 * z2 / sep;
        double closeness = rStart > rMin * 1.0001 ? 1 - Math.log(sep / rMin) / Math.log(rStart / rMin) : 1;
        String[] speed = speed(r.speed);
        List<FusionDashboard.Dial> dials = List.of(
                new FusionDashboard.Dial("force", "répulsion", number(push), "N", 1 + Math.log10(Math.max(push, 1e-30) / fContact) / 4),
                new FusionDashboard.Dial("distance", "distance", fmt(sep), "fm", closeness),
                new FusionDashboard.Dial("speed", "vitesse relative", speed[0], speed[1], v0 > 0 ? r.speed / v0 : 0));
        List<FusionDashboard.Bar> bars = new ArrayList<>();
        double barrier = run.getBarrierMev();
        if (barrier > 0) bars.add(new FusionDashboard.Bar("wall", "mur de Coulomb · V/B", fmt(100 * coulomb / barrier) + " %",
                coulomb / barrier, Math.min(1, e / barrier), "E"));
        for (FusionRenderer.Tag t : r.tags) {
            if (bars.size() >= 3 || t.alpha() < .5 || t.body().a() < 2 || t.body().z() <= 0) continue;
            bars.add(new FusionDashboard.Bar("b2:" + t.body().z() + ":" + t.body().a(), "compression de " + symbol.apply(t.body().z(), t.body().a()) + " (β₂)",
                    deformation(t.beta2()), Math.abs(t.beta2()) / .3, -1, ""));
        }
        List<String> formulas = new ArrayList<>();
        formulas.add("F = kZ₁Z₂e²/r² = " + force(push));
        formulas.add(touched && r.stage.equals("scattered")
                ? "K + V_C + V_N = " + energy(e * 1000) + " · V total = " + energy(r.potential * 1000)
                : "V = kZ₁Z₂e²/r = " + energy(coulomb * 1000) + "  ·  K = E − V");
        if (!Double.isNaN(r.eta)) {
            formulas.add("η = Z₁Z₂α/β = " + fmt(r.eta) + (r.eta < 1 ? " : une onde" : r.eta > 10 ? " : une bille" : " : entre onde et bille"));
            formulas.add("λ = h/p = " + (Double.isInfinite(r.lambda) || r.lambda > 1e5 ? "∞ (il s'arrête)" : fmt(r.lambda) + " fm")
                    + "  ·  e^(−2πη) = " + scientificShort(Math.exp(-2 * Math.PI * r.eta)));
        }
        if (r.stage.equals("scattered")) {
            if (touched) formulas.add("rebond au contact : pᵣ sortant = −pᵣ entrant · E et impulsion conservées");
            else {
                double d0 = KE * z1 * z2 / Math.max(e, 1e-9), b = run.getImpactFm();
                double theta = b <= 1e-9 ? 180 : Math.toDegrees(2 * Math.atan(d0 / (2 * b)));
                formulas.add("Rutherford : θ = 2·arctan(kZ₁Z₂e²/2Eb) = " + fmt(theta) + "°");
            }
        }
        return new FusionDashboard.Board(r.stage.equals("scattered") ? "REBOND" : "APPROCHE", split, dials, bars, formulas);
    }

    private FusionDashboard.Board tunnel(CollisionRun run) {
        double e = run.getEnergyKev() / 1000, barrier = run.getBarrierMev(), contact = run.getContactFm();
        double psi = FusionRenderer.waveAmplitude(run, contact), psi2 = Double.isNaN(psi) ? Double.NaN : psi * psi;
        double t = run.getWkbTransmission(), p = run.getProbability();
        List<FusionDashboard.Dial> dials = new ArrayList<>();
        dials.add(new FusionDashboard.Dial("eb", "énergie / mur", barrier > 0 ? fmt(e / barrier) : "—", "E / B", barrier > 0 ? e / barrier : 0));
        dials.add(new FusionDashboard.Dial("psi", "onde au contact", Double.isNaN(psi2) ? "—" : scientificShort(psi2), "|ψ|²",
                Double.isNaN(psi2) ? 0 : 1 + Math.log10(Math.max(psi2, 1e-300)) / 12));
        dials.add(new FusionDashboard.Dial("p", "probabilité", probability(p), "ce tir", 1 + Math.log10(Math.max(p, 1e-300)) / 12));
        List<FusionDashboard.Bar> bars = new ArrayList<>();
        if (t > 0) bars.add(new FusionDashboard.Bar("wkb", "pénétrabilité WKB · T", scientificShort(t), 1 + Math.log10(t) / 20, -1, ""));
        double mu = Math.max(run.getReducedMassMev(), 1), eta = sommerfeld(z1, z2, e, mu), gamow = Math.exp(-2 * Math.PI * eta);
        bars.add(new FusionDashboard.Bar("gamow", "facteur de Gamow · e^(−2πη)", scientificShort(gamow), 1 + Math.log10(Math.max(gamow, 1e-300)) / 20, -1, ""));
        List<String> formulas = new ArrayList<>();
        formulas.add("κ(r) = √(2μ(V(r) − E))/ħ");
        if (t > 0) formulas.add("T = exp(−2∫κ dr) = " + scientificShort(t));
        double turn = KE * z1 * z2 / Math.max(e, 1e-9);
        formulas.add(e < barrier ? I18n.text("le mur : de r = ") + fmt(contact) + I18n.text(" à ") + fmt(turn) + I18n.text(" fm (là où V = E)") : "E > B : l'onde passe au-dessus du mur");
        return new FusionDashboard.Board("EFFET TUNNEL", null, dials, bars, formulas);
    }

    private FusionDashboard.Board merge(CollisionRun run, FusionRenderer.Report r) {
        double k = Math.max(0, r.kinetic), heat = Math.max(0, r.heat);
        var split = new FusionDashboard.Split("ÉLAN → CHALEUR", energy((k + heat) * 1000), List.of(
                new FusionDashboard.Part("élan K", energy(k * 1000), k, CYAN),
                new FusionDashboard.Part("chaleur", energy(heat * 1000), heat, HOT)));
        double strongMax = run.getDepthMev() / (4 * .63) * MEV_FM_N;
        double overlap = Math.max(0, Math.min(1, 1 - r.separation / Math.max(run.getContactFm(), 1e-3)));
        List<FusionDashboard.Dial> dials = List.of(
                new FusionDashboard.Dial("strong", "force forte", number(r.strong), "N", strongMax > 0 ? r.strong / strongMax : 0),
                new FusionDashboard.Dial("heat", "chaleur", fmt(heat), "MeV", eStar0 > 0 ? heat / eStar0 : 0),
                new FusionDashboard.Dial("overlap", "recouvrement", fmt(100 * overlap), "%", overlap));
        List<FusionDashboard.Bar> bars = new ArrayList<>();
        double sep = Math.max(r.separation, 1e-3), push = KE * z1 * z2 / (sep * sep) * MEV_FM_N;
        bars.add(new FusionDashboard.Bar("force", "répulsion restante", force(Math.min(push, fContact)), 1 + Math.log10(Math.min(push, fContact) / fContact) / 4, -1, ""));
        String[] speed = speed(r.speed);
        bars.add(new FusionDashboard.Bar("speed", "vitesse relative", speed[0] + " " + speed[1], v0 > 0 ? r.speed / v0 : 0, -1, ""));
        List<String> formulas = new ArrayList<>();
        if (r.stage.equals("contact")) {
            formulas.add("Z₁ + Z₂ = " + (z1 + z2) + (z1 + z2 > 118 ? " > 118 : pas de noyau composé" : ""));
            if (run.getOutcome().equals("elastic"))
                formulas.add(z1 == 1 && z2 == 1 && a1 == 1 && a2 == 1
                        ? "²He non lié : sans conversion faible, p + p se séparent" : "pas de capture : rebond élastique au contact");
            else if (run.getCompoundDurationS() > 0) formulas.add("ils tournent ensemble ≈ " + duration(run.getCompoundDurationS()));
        } else {
            formulas.add("μr̈ = −dV/dr − γ(r)·ṙ");
            formulas.add("γ(r) = K·(dV_N/dr)²  (Gross–Kalinowski)");
            formulas.add("chaleur = ∫γ·ṙ² dt = " + fmt(heat) + " MeV");
        }
        return new FusionDashboard.Board(r.stage.equals("contact") ? "CONTACT" : "SOUDURE", split, dials, bars, formulas);
    }

    /** La fonte des formes (non tracée) : on montre ce que sera le noyau composé. */
    private FusionDashboard.Board melt(CollisionRun run, FusionRenderer.Report r) {
        int a = Math.max(1, run.getCompoundA());
        double t = temperature(eStar0, a), p = pressure(eStar0, a);
        List<FusionDashboard.Dial> dials = List.of(
                new FusionDashboard.Dial("ex", "excitation E*", fmt(eStar0), "MeV", 1),
                new FusionDashboard.Dial("t", "température", fmt(t), "MeV", 1),
                new FusionDashboard.Dial("p", "pression", scientificShort(p), "Pa", (Math.log10(Math.max(p, 1)) - 28) / 6));
        List<String> formulas = List.of("E* = E + Q = " + fmt(eStar0) + " MeV",
                "forme interpolée : cette étape n'est pas tracée",
                I18n.text("chaleur de la soudure calculée : ") + fmt(r.heat) + " MeV");
        return new FusionDashboard.Board("FONTE", null, dials, List.of(), formulas);
    }

    private FusionDashboard.Board hot(CollisionRun run, FusionRenderer.Report r) {
        FusionRenderer.Tag hot = null;
        for (FusionRenderer.Tag t : r.tags) if (t.alpha() > .4 && t.body().z() > 0 && (hot == null || t.body().a() > hot.body().a())) hot = t;
        int a = hot == null ? Math.max(1, run.getCompoundA()) : hot.body().a();
        double ex = hot == null || hot.look() == null ? eStar0 : hot.look().excitation();
        double t = temperature(ex, a), t0 = temperature(eStar0, Math.max(1, run.getCompoundA())), p = pressure(ex, a);
        List<FusionDashboard.Dial> dials = List.of(
                new FusionDashboard.Dial("ex", "excitation E*", fmt(ex), "MeV", eStar0 > 0 ? ex / eStar0 : 0),
                new FusionDashboard.Dial("t", "température", fmt(t), "MeV", t0 > 0 ? t / t0 : 0),
                new FusionDashboard.Dial("p", "pression", scientificShort(p), "Pa", (Math.log10(Math.max(p, 1)) - 28) / 6));
        List<FusionDashboard.Bar> bars = new ArrayList<>();
        double spin = hot == null || hot.look() == null ? 0 : hot.look().spin();
        bars.add(new FusionDashboard.Bar("spin", "spin J du noyau chaud", spinText(spin), spin / 20, -1, ""));
        if (r.stage.equals("evaporation")) {
            CollisionSample now = sampleAt(run, r.time);
            double carried = 0;
            int emitted = 0;
            CollisionBody heavy = null;
            for (CollisionBody b : now.getBodiesList()) if (b.getZ() > 0 && (heavy == null || b.getA() > heavy.getA())) heavy = b;
            for (CollisionBody b : now.getBodiesList()) {
                if (b == heavy) continue;
                carried += b.getKineticMev();
                emitted++;
            }
            double available = Math.max(1e-9, run.getQMev() + run.getEnergyKev() / 1000);
            bars.add(new FusionDashboard.Bar("carried", I18n.text("emporté par ") + emitted + I18n.text(" émission") + (emitted > 1 ? "s" : ""), energy(carried * 1000),
                    carried / available, -1, ""));
        }
        List<String> formulas = new ArrayList<>();
        formulas.add("T = √(8E*/A) = " + fmt(t) + " MeV = " + scientificShort(t * MEV_K) + " K");
        formulas.add("soit " + times(t * MEV_K / SUN_CORE_K) + I18n.text(" la température du cœur solaire"));
        formulas.add("P = ⅔·E*/V = " + scientificShort(p) + " Pa");
        formulas.add("soit " + times(p / SUN_CORE_PA) + I18n.text(" la pression du cœur solaire"));
        if (r.stage.equals("evaporation")) formulas.add("Γₓ ∝ (2sₓ+1)·μₓ·ε·σ(ε)·ρ(E* − Bₓ − ε)");
        else formulas.add("J = j₁ + j₂ + ℓ");
        return new FusionDashboard.Board(r.stage.equals("evaporation") ? "DÉSEXCITATION" : "NOYAU COMPOSÉ", null, dials, bars, formulas);
    }

    private FusionDashboard.Board products(CollisionRun run) {
        CollisionSample last = run.getSamples(run.getSamplesCount() - 1);
        Map<String, double[]> groups = new LinkedHashMap<>();
        Map<String, Color> colors = new LinkedHashMap<>();
        double total = 0, gamma = 0, fastest = 0;
        for (CollisionBody b : last.getBodiesList()) {
            String key = particle(b);
            groups.computeIfAbsent(key, x -> new double[2])[0] += b.getKineticMev();
            groups.get(key)[1]++;
            colors.putIfAbsent(key, colorOf(b));
            total += b.getKineticMev();
            if (b.getA() == 0 && !b.getKind().equals("positron")) gamma += b.getKind().equals("neutrino") ? 0 : b.getKineticMev();
            else if (b.getVelocityCount() >= 3) fastest = Math.max(fastest, Math.sqrt(b.getVelocity(0) * b.getVelocity(0)
                    + b.getVelocity(1) * b.getVelocity(1) + b.getVelocity(2) * b.getVelocity(2)) / C);
        }
        List<FusionDashboard.Part> parts = new ArrayList<>();
        groups.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0])).limit(4).forEach(g -> parts.add(
                new FusionDashboard.Part((g.getValue()[1] > 1 ? (int) g.getValue()[1] + " " : "") + g.getKey(), energy(g.getValue()[0] * 1000),
                        g.getValue()[0], colors.get(g.getKey()))));
        double q = run.getQMev();
        String[] speed = speed(fastest);
        List<FusionDashboard.Dial> dials = List.of(
                new FusionDashboard.Dial("q", "masse → énergie", (q >= 0 ? "+" : "") + fmt(q), "MeV · Q", Math.log10(1 + Math.abs(q)) / Math.log10(201)),
                new FusionDashboard.Dial("fast", "le plus rapide", speed[0], speed[1], Math.sqrt(Math.min(1, fastest / .3))),
                new FusionDashboard.Dial("gamma", "lumière γ", fmt(gamma), "MeV", total > 0 ? gamma / total : 0));
        List<String> formulas = new ArrayList<>();
        formulas.add(I18n.text("Q = (Σm avant − Σm après)·c² = ") + fmt(q) + " MeV");
        formulas.add(I18n.text("1 réaction = ") + scientificShort(Math.abs(q) * MEV_J) + " J");
        List<CollisionBody> massive = new ArrayList<>();
        for (CollisionBody b : last.getBodiesList()) if (b.getMassMev() > 0 && b.getA() > 0) massive.add(b);
        if (massive.size() == 2 && last.getBodiesCount() == 2 && massive.get(1).getKineticMev() > 0) {
            CollisionBody one = massive.get(0), two = massive.get(1);
            formulas.add("K(" + particle(one) + ")/K(" + particle(two) + ") = m(" + particle(two) + ")/m(" + particle(one) + ") = "
                    + fmt(one.getKineticMev() / two.getKineticMev()) + I18n.text("  (impulsions opposées)"));
        }
        return new FusionDashboard.Board("PRODUITS", new FusionDashboard.Split("ÉNERGIE EMPORTÉE", energy(total * 1000), parts), dials, List.of(), formulas);
    }

    // ================================================================== la loi de l'instant, en grand
    private static final Color ROSE = Color.web("#FF6B8E"), GOLD = Color.web("#FFC85A"), SKY = Color.web("#5FD3F5");

    /**
     * La loi qui gouverne l'instant, composée pour être posée sur la scène : sa forme, sa valeur, la même avec les
     * nombres, et sa réponse à un changement — ce que devient le résultat si l'on double la distance, l'énergie, le
     * temps. Ces réponses sont calculées avec la loi elle-même, pas écrites à la main.
     */
    FormulaCard.Formula law(CollisionRun run, CollisionTimeline time, FusionRenderer.Report r) {
        prepare(run);
        double e = run.getEnergyKev() / 1000, sep = Math.max(r.separation, 1e-3);
        switch (r.stage) {
            case "approach", "scattered" -> {
                double force = KE * z1 * z2 / (sep * sep) * MEV_FM_N;
                return new FormulaCard.Formula("RÉPULSION DE COULOMB", "F = k", "|q₁ q₂|", "r²", force(force),
                        "k · " + z1 + "e × " + z2 + "e / (" + fmt(sep) + " fm)²  ·  " + s1 + I18n.text(" et ") + s2,
                        "r → 2r", "F → F/4", ROSE);
            }
            case "tunnel" -> {
                // La réponse du passage à un peu plus d'énergie, par le facteur de Gamow lui-même.
                double mu = Math.max(run.getReducedMassMev(), 1);
                double now = Math.exp(-2 * Math.PI * sommerfeld(z1, z2, e, mu));
                double hotter = Math.exp(-2 * Math.PI * sommerfeld(z1, z2, e * 1.2, mu));
                return new FormulaCard.Formula("PASSAGE PAR EFFET TUNNEL", "T = exp", "−2∫κ dr", "", scientificShort(run.getWkbTransmission()),
                        "κ = √(2μ(V − E))/ħ  ·  E = " + energy(run.getEnergyKev()) + I18n.text(" sous un mur de ") + energy(run.getBarrierMev() * 1000),
                        "E → 1,2 E", "T × " + number(hotter / Math.max(now, 1e-300)), SKY);
            }
            case "merge" -> {
                if (!time.computedMerge())
                    return new FormulaCard.Formula("NOYAU FORMÉ", "E* = E + Q", "", "", fmt(eStar0) + " MeV",
                            "l'élan du choc et l'énergie de liaison chauffent le noyau", "", "", GOLD);
                return new FormulaCard.Formula("SOUDURE : FRICTION NUCLÉAIRE", "μ r̈ = −", "dV", "dr", fmt(r.heat) + " MeV",
                        "− γ(r)·ṙ  ·  γ = K(dV_N/dr)² (Gross–Kalinowski)  ·  chaleur = ∫γṙ² dt",
                        "élan perdu", "chaleur gagnée", GOLD);
            }
            case "relax" -> {
                return new FormulaCard.Formula("LES FORMES SE FONDENT", "E* = E + Q", "", "", fmt(eStar0) + " MeV",
                        "étape non tracée : la forme est interpolée", "", "", GOLD);
            }
            case "compound" -> {
                int a = Math.max(1, run.getCompoundA());
                double t = temperature(eStar0, a);
                return new FormulaCard.Formula("NOYAU COMPOSÉ : SA TEMPÉRATURE", "T = ", "√(8E*/A)", "", fmt(t) + " MeV",
                        "E* = " + fmt(eStar0) + " MeV · A = " + a + "  ·  soit " + scientificShort(t * MEV_K) + " K",
                        "E* → 4 E*", "T → 2 T", GOLD);
            }
            case "evaporation" -> {
                return new FormulaCard.Formula("ÉVAPORATION DE WEISSKOPF", "Γₓ ∝ ", "(2sₓ+1) μₓ ε σ(ε) ρ(E*−Bₓ−ε)", "", fmt(eStar0) + I18n.text(" MeV à dissiper"),
                        "chaque émission emporte son énergie et laisse un noyau plus froid", "E* ↓", "émissions ↓", GOLD);
            }
            case "products" -> {
                double q = run.getQMev();
                return new FormulaCard.Formula("MASSE CHANGÉE EN ÉNERGIE", "Q = Δm c²", "", "", fmt(q) + " MeV",
                        "Σ masses avant − Σ masses après  ·  Σp⃗ = 0",
                        "1 réaction", scientificShort(Math.abs(q) * MEV_J) + " J", GOLD);
            }
            case "contact" -> {
                return new FormulaCard.Formula(run.getOutcome().equals("elastic") ? "CONTACT SANS ÉTAT LIÉ" : "SYSTÈME DINUCLÉAIRE",
                        "Z₁ + Z₂ = ", (z1 + z2) + "", "", energy(e * 1000) + I18n.text(" rendus"),
                        run.getOutcome().equals("elastic") ? "la vitesse radiale change de signe, l'énergie et l'impulsion sont conservées"
                                : "au-delà de Z = 118, aucun noyau composé ne tient",
                        "", "", ROSE);
            }
            default -> {
                return null;
            }
        }
    }

    /** Avant le tir : le mur à franchir. */
    static FormulaCard.Formula readyLaw(CollisionNucleus a, CollisionNucleus b, double energyKev, double barrierKev) {
        if (a == null || b == null || barrierKev <= 0) return null;
        double contact = 1.2 * (Math.cbrt(Math.max(1, a.getA())) + Math.cbrt(Math.max(1, b.getA())));
        return new FormulaCard.Formula("LE MUR À FRANCHIR", "B = k", "Z₁ Z₂ e²", "R₁ + R₂", energy(barrierKev),
                "au contact, R₁ + R₂ ≈ " + fmt(contact) + I18n.text(" fm  ·  le tir est réglé à ") + energy(energyKev),
                "E = " + energy(energyKev), energyKev >= barrierKev ? "au-dessus du mur" : "sous le mur : effet tunnel", ROSE);
    }

    // ================================================================== la ligne d'équations
    /** La loi qui gouverne l'instant montré, avec ses valeurs : une ligne sous la scène. */
    String equation(CollisionRun run, CollisionTimeline time, FusionRenderer.Report r) {
        prepare(run);
        double e = run.getEnergyKev() / 1000, sep = Math.max(r.separation, 1e-3);
        return switch (r.stage) {
            case "scattered" -> touched
                    ? I18n.text("Séparation après contact : K = ") + energy(Math.max(0, r.kinetic) * 1000)
                    + "     V = " + energy(r.potential * 1000) + "     K + V = " + energy(e * 1000)
                    + "     r = " + fmt(sep) + " fm"
                    : "F = kZ₁Z₂e²/r² = " + force(KE * z1 * z2 / (sep * sep) * MEV_FM_N) + "     V = kZ₁Z₂e²/r = "
                    + energy(KE * z1 * z2 / sep * 1000) + "     K = E − V = " + energy(Math.max(0, r.kinetic) * 1000) + "     r = " + fmt(sep) + " fm";
            case "approach" -> "F = kZ₁Z₂e²/r² = " + force(KE * z1 * z2 / (sep * sep) * MEV_FM_N) + "     V = kZ₁Z₂e²/r = "
                    + energy(KE * z1 * z2 / sep * 1000) + "     K = E − V = " + energy(Math.max(0, r.kinetic) * 1000) + "     r = " + fmt(sep) + " fm";
            case "tunnel" -> "T = exp(−2∫κ dr) = " + scientificShort(run.getWkbTransmission()) + "     κ = √(2μ(V − E))/ħ     P(ce tir) = "
                    + probability(run.getProbability());
            case "merge" -> time.computedMerge() ? "μr̈ = −dV/dr − γ(r)·ṙ     γ = K·(dV_N/dr)²     ∫γṙ² dt = " + fmt(r.heat) + " MeV     r = " + fmt(sep) + " fm"
                    : "E* = E + Q = " + fmt(eStar0) + I18n.text(" MeV     soudure ≈ 10⁻²² s (mise en scène)");
            case "relax" -> "E* = E + Q = " + fmt(eStar0) + " MeV     forme interpolée (non tracée)";
            case "compound" -> "E* = E + Q = " + fmt(eStar0) + " MeV     T = √(8E*/A) = " + fmt(temperature(eStar0, run.getCompoundA())) + " MeV     J = j₁ + j₂ + ℓ";
            case "evaporation" -> "Γₓ ∝ (2sₓ+1)·μₓ·ε·σ(ε)·ρ(E* − Bₓ − ε)     (Weisskopf" + (run.getOutcome().equals("fission") ? ", Bohr–Wheeler" : "") + ")";
            case "products" -> "Q = (Σm avant − Σm après)·c² = " + fmt(run.getQMev()) + " MeV     Σp⃗ = 0     E = " + energy(e * 1000) + " + Q";
            case "contact" -> run.getOutcome().equals("elastic")
                    ? I18n.text("Contact sans capture : pᵣ sortant = −pᵣ entrant     K + V = ") + energy(e * 1000)
                    : "Z₁ + Z₂ = " + (z1 + z2) + I18n.text("     système dinucléaire : il tourne ≈ ") + duration(Math.max(run.getCompoundDurationS(), 1e-21)) + I18n.text(" puis se sépare");
            default -> "";
        };
    }

    /** Avant le tir. */
    static String readyEquation(CollisionNucleus a, CollisionNucleus b, double energyKev, double barrierKev) {
        if (a == null || b == null || energyKev <= 0) return "";
        double ma = a.getMassMev() > 0 ? a.getMassMev() : a.getA() * 931.494, mb = b.getMassMev() > 0 ? b.getMassMev() : b.getA() * 931.494;
        double eta = sommerfeld(a.getZ(), b.getZ(), energyKev / 1000, ma * mb / (ma + mb));
        return "B = " + energy(barrierKev) + "     E = " + energy(energyKev) + "     η = Z₁Z₂α/β = " + fmt(eta) + "     e^(−2πη) = "
                + scientificShort(Math.exp(-2 * Math.PI * eta));
    }

    // ================================================================== la sonde
    /** Ce que vaut le champ au point d'écran (x, y), tel que la scène le montre. */
    Card probe(FusionRenderer.Report r, double x, double y) {
        if (r.map) return mapProbe(r, x, y);
        List<FusionRenderer.Source> q = r.sources;
        if (q.isEmpty()) return new Card("VIDE", "Aucune charge visible : pas de champ électrique ici.", List.of(), Color.web("#8FA7C4"), List.of());
        int n = q.size(), inside = -1;
        double[] ex = new double[n], ey = new double[n], en = new double[n], dist = new double[n], level = new double[n], uk = new double[n];
        double tx = 0, ty = 0, phi = 0;
        for (int k = 0; k < n; k++) {
            FusionRenderer.Source s = q.get(k);
            double dx = (x - s.x()) * s.fmPerPx(), dy = (y - s.y()) * s.fmPerPx(), d = Math.hypot(dx, dy), radius = s.radius();
            dist[k] = d;
            // Dans le plasma : Debye–Hückel, φ ∝ e^(−r/λ)/r et E ∝ e^(−r/λ)(1 + r/λ)/r² (λ = ∞ : Coulomb).
            double screen = Math.exp(-d / r.debyeFm);
            double field = (d >= radius ? FIELD * s.charge() / (d * d) : FIELD * s.charge() * d / (radius * radius * radius))
                    * screen * (1 + Math.max(d, radius) / r.debyeFm);
            ex[k] = d > 1e-9 ? field * dx / d : 0;
            ey[k] = d > 1e-9 ? field * dy / d : 0;
            en[k] = field;
            uk[k] = (d >= radius ? s.charge() / d : s.charge() * (3 * radius * radius - d * d) / (2 * radius * radius * radius)) * screen;
            phi += POTENTIAL * uk[k];
            double floor = Math.max(r.pressureMin, 1e-12);
            level[k] = Math.max(0, Math.min(1, Math.log10(1 + EPS0 * field * field / (2 * floor)) / Math.log10(1 + 1e4)));
            tx += ex[k];
            ty += ey[k];
            if (d < radius && (inside < 0 || d / radius < dist[inside] / q.get(inside).radius())) inside = k;
        }
        double total = Math.hypot(tx, ty), u = EPS0 / 2 * total * total;
        List<double[]> vectors = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (en[k] <= 0 || level[k] < .02) continue;
            Color c = FusionRenderer.bodyColor(q.get(k).body().z(), q.get(k).body().a());
            vectors.add(new double[]{ex[k], ey[k], c.getRed(), c.getGreen(), c.getBlue()});
        }
        if (n > 1 && total > 0) vectors.add(new double[]{tx, ty, 1, 1, 1});
        if (inside >= 0) return nucleusCard(r, q, inside, dist[inside], en[inside], phi, vectors);
        for (double[] saddle : r.saddles)
            if (Math.hypot(x - saddle[0], y - saddle[1]) < 12) return colCard(r, q, saddle, vectors);
        int first = 0;
        for (int k = 1; k < n; k++) if (level[k] > level[first]) first = k;
        String name = name(q.get(first).body());
        Color accent = FusionRenderer.bodyColor(q.get(first).body().z(), q.get(first).body().a());
        List<String> lines = new ArrayList<>();
        // La zone du col : la même mesure que la carte — deux potentiels comparables, deux champs qui s'opposent.
        int p1 = -1, p2 = -1;
        for (int k = 0; k < n; k++) {
            if (p1 < 0 || uk[k] > uk[p1]) {
                p2 = p1;
                p1 = k;
            } else if (p2 < 0 || uk[k] > uk[p2]) p2 = k;
        }
        if (p2 >= 0 && en[p1] > 0 && en[p2] > 0 && uk[p1] > 0) {
            double share = Math.min(1, 2 * uk[p2] / (uk[p1] + uk[p2]));
            double dot = ex[p1] * ex[p2] + ey[p1] * ey[p2], cos = dot / (en[p1] * en[p2]);
            double zone = Math.pow(share, 1.2) * Math.pow(Math.max(0, (1 - cos) / 2), 2), potential = phi / 1e6;
            if (total < .2 * Math.min(en[p1], en[p2]) && cos < -.8)
                return colCard(r, q, new double[]{x, y, potential, p1, p2}, vectors);
            if (zone > .15) {
                String n1 = name(q.get(p1).body()), n2 = name(q.get(p2).body());
                double top = Math.max(r.potentialTop, 1e-12);
                double heat = Math.max(0, Math.min(1, 1 + Math.log10(Math.max(potential, 1e-30) / top) / FusionRenderer.POTENTIAL_DECADES));
                lines.add("U = eφ = U(" + n1 + ") + U(" + n2 + ") = " + energy(KE * uk[p1] * 1000) + " + " + energy(KE * uk[p2] * 1000)
                        + " = " + energy(potential * 1000));
                lines.add("Uᵢ = kZᵢe²/rᵢ : r(" + n1 + ") = " + fmt(dist[p1]) + " fm · r(" + n2 + ") = " + fmt(dist[p2]) + " fm");
                lines.add(screening(r.debyeFm, dist[p1]));
                lines.add("E(" + n1 + ") = " + scientificShort(en[p1]) + " V/m · E(" + n2 + ") = " + scientificShort(en[p2]) + " V/m · angle "
                        + fmt(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))))) + "°");
                lines.add("ε₀E₁·E₂ = " + scientificShort(EPS0 * dot) + " J/m³" + (dot < 0 ? " (négatif : ils s'opposent)" : ""));
                if (r.potentialTop > 0) lines.add("col au contact : " + energy(r.potentialTop * 1000) + I18n.text(" · ici ") + fmt(100 * potential / r.potentialTop) + " %");
                lines.add(I18n.text("un proton posé ici : F = eE = ") + force(ELEMENTARY * total));
                return new Card("ZONE DU COL", I18n.text("Entre les deux noyaux, le potentiel combiné : l'énergie qu'aurait une charge +e posée ici. Leurs ")
                        + I18n.text("champs s'y opposent ; la couleur (bleu → blanc) suit U, les lignes sont des isopotentielles."), lines,
                        FusionRenderer.heatColor(heat), vectors);
            }
        }
        boolean far = level[first] < .06;
        lines.add("r = " + fmt(dist[first]) + I18n.text(" fm du centre de ") + name);
        lines.add(screening(r.debyeFm, dist[first]));
        lines.add("E = kZe/r² = " + scientificShort(en[first]) + " V/m" + (n > 1 ? " · total " + scientificShort(total) + " V/m" : ""));
        lines.add("u = ε₀E²/2 = " + scientificShort(u) + " J/m³");
        lines.add("= " + times(u / SUN_CORE_PA) + I18n.text(" la pression au cœur du Soleil"));
        lines.add("φ = Σ kZe/r = " + volts(phi) + " · U = eφ = " + energy(phi / 1e3) + I18n.text(" (un proton posé ici)"));
        lines.add(I18n.text("un proton posé ici : F = eE = ") + force(ELEMENTARY * total));
        return new Card(far ? "LOIN DES NOYAUX" : "HALO DE " + name, far ? "Le champ s'y affaiblit en 1/r² ; l'énergie qu'il porte, en 1/r⁴, y est déjà infime."
                : I18n.text("Le champ électrique de ce noyau (charge +") + q.get(first).body().z() + I18n.text("e). Son énergie ε₀E²/2 décroît en 1/r⁴ : le halo ")
                + I18n.text("s'éteint à quelques rayons."), lines, accent, vectors);
    }

    /**
     * Sur la carte, les distances sont comprimées : un champ ou un potentiel calculé à ces distances ne voudrait rien dire.
     * La sonde donne donc ce que la carte garde de vrai — pour le corps touché : sa distance vraie au centre, sa vitesse,
     * son énergie cinétique, sa direction de vol.
     */
    private Card mapProbe(FusionRenderer.Report r, double x, double y) {
        FusionRenderer.Tag best = null;
        double nearest = 34;
        for (FusionRenderer.Tag t : r.tags) {
            double d = Math.hypot(x - t.x(), y - t.y()) - t.radius();
            if (t.alpha() > .3 && d < nearest) {
                nearest = d;
                best = t;
            }
        }
        if (best == null) return new Card("CARTE", I18n.text("Distances comprimées (échelle logarithmique) : la sonde ne mesure ici que les corps. ")
                + I18n.text("Pour sonder le champ, éteignez la carte (bouton « Carte »)."), List.of(), Color.web("#FFC46B"), List.of());
        Body b = best.body();
        double beta = Math.min(.999999, b.velocity().norm() / C), distance = best.offset().norm();
        double mass = best.look() != null && best.look().mass() > 0 ? best.look().mass() : Math.max(1, b.a()) * 931.494;
        boolean massless = b.a() == 0 && (best.look() == null || !"positron".equals(best.look().kind()));
        double kinetic = massless ? 0 : (1 / Math.sqrt(1 - beta * beta) - 1) * mass;
        List<String> lines = new ArrayList<>();
        lines.add(distance < 1e-9 ? "le centre de la carte : l'élément créé" : "distance vraie au centre : " + lengthText(distance * 1e-15)
                + " (" + scientificShort(distance) + " fm)");
        lines.add("vitesse : " + (massless ? "c" : ResultView.percent(beta)));
        if (!massless && kinetic > 0) lines.add(I18n.text("énergie cinétique K = (γ − 1)mc² = ") + energy(kinetic * 1000));
        if (distance > 1e-9) {
            V u = best.offset().unit();
            lines.add("direction de vol : (" + fmt(u.x()) + " ; " + fmt(u.y()) + " ; " + fmt(u.z()) + ")");
        }
        if (r.mapCompression > 1.05 && distance > 1e-9)
            lines.add(I18n.text("affiché comprimé : le plus éloigné est à ") + scientificShort(r.mapCompression) + I18n.text(" × sa place"));
        return new Card(name(b).toUpperCase(Locale.ROOT), I18n.text("Sur la carte, ce qui reste exact : la direction, la vitesse, l'énergie et ")
                + I18n.text("la distance vraie. Les distances affichées sont comprimées."), lines, FusionRenderer.bodyColor(b.z(), b.a()), List.of());
    }

    private Card nucleusCard(FusionRenderer.Report r, List<FusionRenderer.Source> q, int k, double d, double field, double phi, List<double[]> vectors) {
        FusionRenderer.Source s = q.get(k);
        int z = s.body().z();
        double radius = s.radius();
        String name = name(s.body());
        List<String> lines = new ArrayList<>();
        lines.add("Z = " + z + " · R = " + fmt(radius) + I18n.text(" fm · ici r = ") + fmt(d) + I18n.text(" fm du centre"));
        lines.add("E = kZe·r/R³ = " + scientificShort(field) + " V/m");
        lines.add("en surface : kZe/R² = " + scientificShort(FIELD * z / (radius * radius)) + " V/m");
        lines.add("φ = kZe(3R² − r²)/2R³ + autres = " + volts(phi) + " · U = eφ = " + energy(phi / 1e3));
        if (q.size() == 2) {
            FusionRenderer.Source o = q.get(1 - k);
            double gap = Math.hypot((s.x() - o.x()) * s.fmPerPx(), (s.y() - o.y()) * s.fmPerPx());
            if (gap > 1e-3) lines.add(I18n.text("répulsion de ") + name(o.body()) + " : F = kZ₁Z₂e²/d² = " + force(KE * z * o.body().z() / (gap * gap) * MEV_FM_N)
                    + " (d = " + fmt(gap) + " fm)");
            if (!Double.isNaN(r.eta)) lines.add("η = " + fmt(r.eta) + " · e^(−2πη) = " + scientificShort(Math.exp(-2 * Math.PI * r.eta)));
        }
        if (Math.abs(s.beta2()) >= 1e-6) lines.add("β₂ = " + deformation(s.beta2()) + I18n.text(" : comprimé le long de l'axe par l'autre"));
        // Le champ externe courbe sa trajectoire : r_L = p/qB (le rayon du cercle de Larmor), bien plus grand que la scène.
        double beta = Math.min(.999999, s.body().velocity().norm() / C);
        if (r.externalFieldT > 0 && beta > 1e-9) {
            double mass = s.look() != null && s.look().mass() > 0 ? s.look().mass() : Math.max(1, s.body().a()) * 931.494;
            double momentum = mass * beta / Math.sqrt(1 - beta * beta);
            lines.add(I18n.text("dans ") + fmt(r.externalFieldT) + " T : r_L = p/qB = " + lengthText(3.33564095e-3 * momentum / (z * r.externalFieldT))
                    + " (× " + scientificShort(3.33564095e-3 * momentum / (z * r.externalFieldT) / 1e-15 / Math.max(1, 2 * s.radius())) + I18n.text(" ce noyau)"));
        }
        // Dans un plasma, il arrache des électrons tout au long de sa route : dE/dx ∝ Z²/v².
        if (r.electronDensityM3 > 0 && beta > 1e-9) {
            double power = PlasmaInset.stoppingPower(z, beta * C, r.electronDensityM3, r.debyeFm * 1e-15);
            if (power > 0) lines.add("ionisation : dE/dx = " + scientificShort(power / MEV_J * 1e-3) + " MeV/mm");
        }
        double spin = s.look() == null ? 0 : s.look().spin();
        if (spin > 0 && Math.abs(s.body().moment()) > 1e-6)
            lines.add("spin " + spinText(spin) + " · μ = " + fmt(s.body().moment()) + I18n.text(" μN · B au pôle ≈ ")
                    + scientificShort(1.0102e12 * Math.abs(s.body().moment()) / Math.pow(radius, 3)) + " T");
        double excitation = s.look() == null ? 0 : s.look().excitation();
        if (excitation > .05) lines.add(I18n.text("excité : E* = ") + fmt(excitation) + " MeV · T = " + fmt(temperature(excitation, s.body().a())) + " MeV");
        return new Card(I18n.text("NOYAU ") + name, I18n.text("La charge +") + z + I18n.text("e est répartie dans la sphère : dedans, le champ croît avec r (E ∝ r) ; ")
                + I18n.text("dehors, il décroît en 1/r²."), lines, FusionRenderer.bodyColor(z, s.body().a()), vectors);
    }

    /**
     * Le col du potentiel combiné : le point de selle de U = eφ où E₁ + E₂ = 0. `saddle` : x, y (px), U (MeV), indices
     * des deux sources.
     */
    private Card colCard(FusionRenderer.Report r, List<FusionRenderer.Source> q, double[] saddle, List<double[]> vectors) {
        List<String> lines = new ArrayList<>();
        double top = Math.max(r.potentialTop, 1e-12);
        double level = Math.max(0, Math.min(1, 1 + Math.log10(Math.max(saddle[2], 1e-30) / top) / FusionRenderer.POTENTIAL_DECADES));
        lines.add("E₁ + E₂ = 0 : aucune force sur une charge test");
        if (Double.isFinite(r.debyeFm)) lines.add(screening(r.debyeFm, q.isEmpty() ? 0
                : Math.hypot((saddle[0] - q.get(0).x()) * q.get(0).fmPerPx(), (saddle[1] - q.get(0).y()) * q.get(0).fmPerPx())));
        lines.add("U = eφ = " + energy(saddle[2] * 1000) + I18n.text(" : l'énergie d'un proton posé ici"));
        int i = (int) saddle[3], j = (int) saddle[4];
        if (i >= 0 && j >= 0 && i < q.size() && j < q.size()) {
            FusionRenderer.Source a = q.get(i), b = q.get(j);
            double d = Math.hypot((a.x() - b.x()) * a.fmPerPx(), (a.y() - b.y()) * a.fmPerPx());
            double r1 = Math.hypot((saddle[0] - a.x()) * a.fmPerPx(), (saddle[1] - a.y()) * a.fmPerPx());
            double r2 = Math.hypot((saddle[0] - b.x()) * b.fmPerPx(), (saddle[1] - b.y()) * b.fmPerPx());
            String na = name(a.body()), nb = name(b.body());
            if (d > 1e-6) {
                lines.add("charges ponctuelles : U = ke²(√Z₁ + √Z₂)²/d = " + energy(KE * Math.pow(Math.sqrt(a.charge()) + Math.sqrt(b.charge()), 2) / d * 1000)
                        + " (d = " + fmt(d) + " fm)");
                lines.add(I18n.text("barrière du choc : V(d) = kZ₁Z₂e²/d = ") + energy(KE * a.charge() * b.charge() / d * 1000));
            }
            if (r2 > 1e-6) lines.add("r(" + na + ")/r(" + nb + ") = " + fmt(r1 / r2) + " = √(Z₁/Z₂) = " + fmt(Math.sqrt(a.charge() / Math.max(b.charge(), 1e-12))));
        }
        if (r.potentialTop > 0) lines.add(I18n.text("surfaces au contact : ce col monterait à ") + energy(r.potentialTop * 1000));
        return new Card("COL DU POTENTIEL", I18n.text("Point de selle de U = eφ : le long de l'axe, U y est minimal ; en travers, maximal. Les deux ")
                + I18n.text("champs s'y annulent : c'est le point le plus bas de la crête qui sépare les deux noyaux."), lines, FusionRenderer.heatColor(level), vectors);
    }

    /** L'écrantage du plasma à la distance r (fm) : e^(−r/λ_D), ou « vide » sans plasma. */
    static String screening(double debyeFm, double distanceFm) {
        if (!Double.isFinite(debyeFm)) return "vide : Coulomb pur, sans écrantage";
        double x = distanceFm / debyeFm;
        return "plasma : λ_D = " + lengthText(debyeFm * 1e-15) + ", e^(−r/λ_D) = " + (x < 1e-3 ? "1 − " + scientificShort(x) : fmt(Math.exp(-x)));
    }

    // ================================================================== mise en forme
    private String name(Body body) {
        return symbol.apply(body.z(), body.a());
    }

    private String particle(CollisionBody b) {
        if (b.getA() == 0) return b.getKind().equals("positron") ? "e⁺" : b.getKind().equals("neutrino") ? "νₑ" : "γ";
        return symbol.apply(b.getZ(), b.getA());
    }

    private static Color colorOf(CollisionBody b) {
        if (b.getA() == 0) return b.getKind().equals("positron") ? Color.web("#FF7AD9") : b.getKind().equals("neutrino") ? Color.web("#B8F5C8") : Color.web("#FFD36A");
        return FusionRenderer.bodyColor(b.getZ(), b.getA());
    }

    /** Une déformation β₂ : trois décimales, ou en notation scientifique quand elle est infime (jamais « −0,000 »). */
    static String deformation(double beta2) {
        if (Math.abs(beta2) >= 1e-3) return String.format(I18n.locale(), "%.3f", beta2);
        return Math.abs(beta2) < 1e-12 ? "0" : scientificShort(beta2);
    }

    /** Une valeur pour un cadran : décimale entre 0,01 et 10⁴, scientifique ailleurs. */
    static String number(double v) {
        if (v == 0) return "0";
        return Math.abs(v) >= .01 && Math.abs(v) < 1e4 ? fmt(v) : scientificShort(v);
    }

    /** Une vitesse (fraction de c) : valeur et unité. */
    static String[] speed(double beta) {
        if (beta >= .001) return new String[]{fmt(100 * beta), "% de c"};
        return new String[]{number(beta * C / 1000), "km/s"};
    }

    /** Un rapport, en « × » : « 2,4·10¹⁵ × », « 3,1 × », « 1/40 ». */
    static String times(double ratio) {
        if (!(ratio > 0)) return "0 ×";
        if (ratio >= 1e4) return scientificShort(ratio) + " ×";
        if (ratio >= 1) return fmt(ratio) + " ×";
        if (ratio >= 1e-4) return "1/" + fmt(1 / ratio) + " de";
        return scientificShort(ratio) + " ×";
    }

    /** Un potentiel en V, kV ou MV. */
    static String volts(double v) {
        double a = Math.abs(v);
        if (a >= 1e6) return fmt(v / 1e6) + " MV";
        if (a >= 1e3) return fmt(v / 1e3) + " kV";
        return fmt(v) + " V";
    }
}
