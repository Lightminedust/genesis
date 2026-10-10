package org.genesis;

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
import org.genesis.FusionField.Body;
import static org.genesis.FusionField.C;
import static org.genesis.ReactorView.duration;
import static org.genesis.ReactorView.energy;
import static org.genesis.ReactorView.fmt;
import static org.genesis.ReactorView.force;
import static org.genesis.ReactorView.probability;
import static org.genesis.ReactorView.scientificShort;
import static org.genesis.ReactorView.spinText;

/**
 * Les calculs montrés pendant la collision : le tableau de bord (ses jauges, étape par étape), la ligne d'équations
 * sous la scène, et la sonde du mode Inspecter (le champ en un point : E₁, E₂, leur somme, ε₀E²/2, φ…). Tout part des
 * échantillons du moteur et de ce que le rendu a dessiné à cette image (mêmes positions, même échelle).
 */
final class FusionReadouts {
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
                new FusionDashboard.Dial("gamow", "passage", scientificShort(gamow), "e^(−2πη)", 1 + Math.log10(Math.max(gamow, 1e-300)) / 20));
        List<String> formulas = new ArrayList<>();
        if (barrier > 0) formulas.add("mur de Coulomb B = " + energy(barrierKev) + " · E = " + energy(energyKev));
        formulas.add("η = Z₁Z₂α/β = " + fmt(eta) + (eta < 1 ? " : une onde" : eta > 10 ? " : une bille" : ""));
        formulas.add("λ = h/p = " + fmt(lambda) + " fm · μ = " + fmt(mu) + " MeV/c²");
        return new FusionDashboard.Board("PRÊT", null, dials, List.of(), formulas);
    }

    /** L'instant montré, selon l'étape. */
    FusionDashboard.Board board(CollisionRun run, CollisionTimeline time, FusionRenderer.Report r) {
        prepare(run);
        return switch (r.stage) {
            case "approach", "scattered" -> approach(run, r);
            case "tunnel" -> tunnel(run);
            case "merge", "contact" -> time.computedMerge() || r.stage.equals("contact") ? merge(run, r) : melt(run, r);
            case "relax" -> melt(run, r);
            case "compound", "evaporation" -> hot(run, r);
            case "products" -> products(run);
            default -> null;
        };
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
        formulas.add(e < barrier ? "le mur : de r = " + fmt(contact) + " à " + fmt(turn) + " fm (là où V = E)" : "E > B : l'onde passe au-dessus du mur");
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
                "chaleur de la soudure calculée : " + fmt(r.heat) + " MeV");
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
            bars.add(new FusionDashboard.Bar("carried", "emporté par " + emitted + " émission" + (emitted > 1 ? "s" : ""), energy(carried * 1000),
                    carried / available, -1, ""));
        }
        List<String> formulas = new ArrayList<>();
        formulas.add("T = √(8E*/A) = " + fmt(t) + " MeV = " + scientificShort(t * MEV_K) + " K");
        formulas.add("soit " + times(t * MEV_K / SUN_CORE_K) + " la température du cœur solaire");
        formulas.add("P = ⅔·E*/V = " + scientificShort(p) + " Pa");
        formulas.add("soit " + times(p / SUN_CORE_PA) + " la pression du cœur solaire");
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
        formulas.add("Q = (Σm avant − Σm après)·c² = " + fmt(q) + " MeV");
        formulas.add("1 réaction = " + scientificShort(Math.abs(q) * MEV_J) + " J");
        List<CollisionBody> massive = new ArrayList<>();
        for (CollisionBody b : last.getBodiesList()) if (b.getMassMev() > 0 && b.getA() > 0) massive.add(b);
        if (massive.size() == 2 && last.getBodiesCount() == 2 && massive.get(1).getKineticMev() > 0) {
            CollisionBody one = massive.get(0), two = massive.get(1);
            formulas.add("K(" + particle(one) + ")/K(" + particle(two) + ") = m(" + particle(two) + ")/m(" + particle(one) + ") = "
                    + fmt(one.getKineticMev() / two.getKineticMev()) + "  (impulsions opposées)");
        }
        return new FusionDashboard.Board("PRODUITS", new FusionDashboard.Split("ÉNERGIE EMPORTÉE", energy(total * 1000), parts), dials, List.of(), formulas);
    }

    // ================================================================== la ligne d'équations
    /** La loi qui gouverne l'instant montré, avec ses valeurs : une ligne sous la scène. */
    String equation(CollisionRun run, CollisionTimeline time, FusionRenderer.Report r) {
        prepare(run);
        double e = run.getEnergyKev() / 1000, sep = Math.max(r.separation, 1e-3);
        return switch (r.stage) {
            case "scattered" -> touched
                    ? "Séparation après contact : K = " + energy(Math.max(0, r.kinetic) * 1000)
                    + "     V = " + energy(r.potential * 1000) + "     K + V = " + energy(e * 1000)
                    + "     r = " + fmt(sep) + " fm"
                    : "F = kZ₁Z₂e²/r² = " + force(KE * z1 * z2 / (sep * sep) * MEV_FM_N) + "     V = kZ₁Z₂e²/r = "
                    + energy(KE * z1 * z2 / sep * 1000) + "     K = E − V = " + energy(Math.max(0, r.kinetic) * 1000) + "     r = " + fmt(sep) + " fm";
            case "approach" -> "F = kZ₁Z₂e²/r² = " + force(KE * z1 * z2 / (sep * sep) * MEV_FM_N) + "     V = kZ₁Z₂e²/r = "
                    + energy(KE * z1 * z2 / sep * 1000) + "     K = E − V = " + energy(Math.max(0, r.kinetic) * 1000) + "     r = " + fmt(sep) + " fm";
            case "tunnel" -> "T = exp(−2∫κ dr) = " + scientificShort(run.getWkbTransmission()) + "     κ = √(2μ(V − E))/ħ     P(ce tir) = "
                    + probability(run.getProbability());
            case "merge" -> time.computedMerge() ? "μr̈ = −dV/dr − γ(r)·ṙ     γ = K·(dV_N/dr)²     ∫γṙ² dt = " + fmt(r.heat) + " MeV     r = " + fmt(sep) + " fm"
                    : "E* = E + Q = " + fmt(eStar0) + " MeV     soudure ≈ 10⁻²² s (mise en scène)";
            case "relax" -> "E* = E + Q = " + fmt(eStar0) + " MeV     forme interpolée (non tracée)";
            case "compound" -> "E* = E + Q = " + fmt(eStar0) + " MeV     T = √(8E*/A) = " + fmt(temperature(eStar0, run.getCompoundA())) + " MeV     J = j₁ + j₂ + ℓ";
            case "evaporation" -> "Γₓ ∝ (2sₓ+1)·μₓ·ε·σ(ε)·ρ(E* − Bₓ − ε)     (Weisskopf" + (run.getOutcome().equals("fission") ? ", Bohr–Wheeler" : "") + ")";
            case "products" -> "Q = (Σm avant − Σm après)·c² = " + fmt(run.getQMev()) + " MeV     Σp⃗ = 0     E = " + energy(e * 1000) + " + Q";
            case "contact" -> run.getOutcome().equals("elastic")
                    ? "Contact sans capture : pᵣ sortant = −pᵣ entrant     K + V = " + energy(e * 1000)
                    : "Z₁ + Z₂ = " + (z1 + z2) + "     système dinucléaire : il tourne ≈ " + duration(Math.max(run.getCompoundDurationS(), 1e-21)) + " puis se sépare";
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
        for (FusionRenderer.Front f : r.fronts) {
            double d = Math.hypot(x - f.x(), y - f.y());
            if (Math.abs(d - f.radiusPx()) > Math.max(8, f.bandPx())) continue;
            double shell = Math.max(.5, f.bandPx() * f.radiusFm() / Math.max(1e-9, f.radiusPx())), radius = Math.max(f.radiusFm(), 1e-3);
            double u = f.releasedMev() * MEV_J / (4 * Math.PI * Math.pow(radius * 1e-15, 2) * shell * 1e-15);
            String[] speed = speed(f.beta());
            return new Card("FRONT DES PRODUITS", "La sphère qu'atteignent à cet instant les produits les plus rapides : l'énergie de la rupture "
                    + "s'éloigne à leur vitesse.", List.of(
                    "R = v·(t − t₀) = " + speed[0] + " " + speed[1] + " × " + scientificShort(f.ageS()) + " s = " + fmt(f.radiusFm()) + " fm",
                    "énergie emportée : " + energy(f.releasedMev() * 1000) + " · " + f.bodies() + " corps",
                    "étalée sur la coquille : u = E/(4πR²·ΔR) ≈ " + scientificShort(u) + " J/m³",
                    "≈ " + times(u / SUN_CORE_PA) + " la pression au cœur du Soleil"), AMBER, List.of());
        }
        List<FusionRenderer.Source> q = r.sources;
        if (q.isEmpty()) return new Card("VIDE", "Aucune charge visible : pas de champ électrique ici.", List.of(), Color.web("#8FA7C4"), List.of());
        int n = q.size(), inside = -1;
        double[] ex = new double[n], ey = new double[n], en = new double[n], dist = new double[n], level = new double[n];
        double tx = 0, ty = 0, phi = 0;
        for (int k = 0; k < n; k++) {
            FusionRenderer.Source s = q.get(k);
            double dx = (x - s.x()) * s.fmPerPx(), dy = (y - s.y()) * s.fmPerPx(), d = Math.hypot(dx, dy), radius = s.radius();
            dist[k] = d;
            double field = d >= radius ? FIELD * s.charge() / (d * d) : FIELD * s.charge() * d / (radius * radius * radius);
            ex[k] = d > 1e-9 ? field * dx / d : 0;
            ey[k] = d > 1e-9 ? field * dy / d : 0;
            en[k] = field;
            phi += d >= radius ? POTENTIAL * s.charge() / d : POTENTIAL * s.charge() * (3 * radius * radius - d * d) / (2 * radius * radius * radius);
            double surface = FIELD * s.body().z() / (radius * radius), ratio = field / surface;
            level[k] = Math.max(0, Math.min(1, Math.log10(1 + ratio * ratio * 1e4) / 4));
            tx += ex[k];
            ty += ey[k];
            if (d < radius && (inside < 0 || d / radius < dist[inside] / q.get(inside).radius())) inside = k;
        }
        double total = Math.hypot(tx, ty), u = EPS0 / 2 * total * total;
        List<double[]> vectors = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (en[k] <= 0 || level[k] < .02) continue;
            Color c = FusionRenderer.haloColor(q.get(k).body().z(), 0);
            vectors.add(new double[]{ex[k], ey[k], c.getRed(), c.getGreen(), c.getBlue()});
        }
        if (n > 1 && total > 0) vectors.add(new double[]{tx, ty, 1, 1, 1});
        if (inside >= 0) return nucleusCard(r, q, inside, dist[inside], en[inside], phi, vectors);
        int first = 0, second = -1;
        for (int k = 1; k < n; k++) if (level[k] > level[first]) first = k;
        for (int k = 0; k < n; k++) if (k != first && (second < 0 || level[k] > level[second])) second = k;
        String name = name(q.get(first).body());
        Color accent = FusionRenderer.haloColor(q.get(first).body().z(), 0);
        List<String> lines = new ArrayList<>();
        if (second >= 0 && en[first] > 0 && en[second] > 0 && level[second] > .03) {
            double dot = ex[first] * ex[second] + ey[first] * ey[second], cos = -dot / (en[first] * en[second]);
            double clash = cos > 0 ? cos * cos * Math.min(level[first], level[second]) : 0, weakest = Math.min(en[first], en[second]);
            String other = name(q.get(second).body());
            if (total < .2 * weakest && cos > .8) {
                lines.add("E₁ (" + name + ") = " + scientificShort(en[first]) + " V/m · E₂ (" + other + ") = " + scientificShort(en[second]) + " V/m");
                lines.add("E = |E₁ + E₂| = " + scientificShort(total) + " V/m : " + fmt(100 * total / weakest) + " % de chacun");
                lines.add("φ = Σ kZe/r = " + volts(phi) + " : le col du potentiel");
                lines.add("r₁/r₂ = " + fmt(dist[first] / dist[second]) + " (là où Z₁/r₁² = Z₂/r₂² : √(Z₁/Z₂) = "
                        + fmt(Math.sqrt(q.get(first).charge() / q.get(second).charge())) + ")");
                return new Card("POINT NEUTRE", "Ici E₁ + E₂ ≈ 0 : les deux poussées s'annulent. Un proton y serait en équilibre — instable : "
                        + "le moindre écart le chasse.", lines, Color.WHITE, vectors);
            }
            if (clash > .12) {
                lines.add("E₁ (" + name + ") = kZ₁e/r₁² = " + scientificShort(en[first]) + " V/m à " + fmt(dist[first]) + " fm");
                lines.add("E₂ (" + other + ") = kZ₂e/r₂² = " + scientificShort(en[second]) + " V/m à " + fmt(dist[second]) + " fm");
                lines.add("angle entre E₁ et E₂ : " + fmt(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -cos))))) + "°");
                lines.add("E = |E₁ + E₂| = " + scientificShort(total) + " V/m");
                lines.add("ε₀E₁·E₂ = " + scientificShort(EPS0 * dot) + " J/m³ (négatif : ils s'opposent)");
                lines.add("u = ε₀E²/2 = " + scientificShort(u) + " J/m³, soit " + times(u / SUN_CORE_PA) + " la pression au cœur du Soleil");
                lines.add("heurt S = cos²θ·min(V₁, V₂) = " + String.format(Locale.FRANCE, "%.2f", clash));
                return new Card("ZONE DE HEURT", "Les deux champs se font face (E₁·E₂ < 0) : leur énergie d'interaction ε₀E₁·E₂ est négative — "
                        + "c'est par là que la répulsion se transmet d'un noyau à l'autre.", lines, Color.web("#FFF0D0"), vectors);
            }
        }
        boolean far = level[first] < .06;
        lines.add("r = " + fmt(dist[first]) + " fm du centre de " + name);
        lines.add("E = kZe/r² = " + scientificShort(en[first]) + " V/m" + (n > 1 ? " · total " + scientificShort(total) + " V/m" : ""));
        lines.add("u = ε₀E²/2 = " + scientificShort(u) + " J/m³");
        lines.add("= " + times(u / SUN_CORE_PA) + " la pression au cœur du Soleil");
        lines.add("φ = Σ kZe/r = " + volts(phi));
        lines.add("un proton posé ici : F = eE = " + force(ELEMENTARY * total));
        return new Card(far ? "LOIN DES NOYAUX" : "HALO DE " + name, far ? "Le champ s'y affaiblit en 1/r² ; l'énergie qu'il porte, en 1/r⁴, y est déjà infime."
                : "Le champ électrique de ce noyau (charge +" + q.get(first).body().z() + "e). Son énergie ε₀E²/2 décroît en 1/r⁴ : le halo "
                + "s'éteint à quelques rayons.", lines, accent, vectors);
    }

    private Card nucleusCard(FusionRenderer.Report r, List<FusionRenderer.Source> q, int k, double d, double field, double phi, List<double[]> vectors) {
        FusionRenderer.Source s = q.get(k);
        int z = s.body().z();
        double radius = s.radius();
        String name = name(s.body());
        List<String> lines = new ArrayList<>();
        lines.add("Z = " + z + " · R = " + fmt(radius) + " fm · ici r = " + fmt(d) + " fm du centre");
        lines.add("E = kZe·r/R³ = " + scientificShort(field) + " V/m");
        lines.add("en surface : kZe/R² = " + scientificShort(FIELD * z / (radius * radius)) + " V/m");
        lines.add("φ = kZe(3R² − r²)/2R³ + autres = " + volts(phi));
        if (q.size() == 2) {
            FusionRenderer.Source o = q.get(1 - k);
            double gap = Math.hypot((s.x() - o.x()) * s.fmPerPx(), (s.y() - o.y()) * s.fmPerPx());
            if (gap > 1e-3) lines.add("répulsion de " + name(o.body()) + " : F = kZ₁Z₂e²/d² = " + force(KE * z * o.body().z() / (gap * gap) * MEV_FM_N)
                    + " (d = " + fmt(gap) + " fm)");
            if (!Double.isNaN(r.eta)) lines.add("η = " + fmt(r.eta) + " · e^(−2πη) = " + scientificShort(Math.exp(-2 * Math.PI * r.eta)));
        }
        if (Math.abs(s.beta2()) >= 1e-6) lines.add("β₂ = " + deformation(s.beta2()) + " : comprimé le long de l'axe par l'autre");
        double spin = s.look() == null ? 0 : s.look().spin();
        if (spin > 0 && Math.abs(s.body().moment()) > 1e-6)
            lines.add("spin " + spinText(spin) + " · μ = " + fmt(s.body().moment()) + " μN · B au pôle ≈ "
                    + scientificShort(1.0102e12 * Math.abs(s.body().moment()) / Math.pow(radius, 3)) + " T");
        double excitation = s.look() == null ? 0 : s.look().excitation();
        if (excitation > .05) lines.add("excité : E* = " + fmt(excitation) + " MeV · T = " + fmt(temperature(excitation, s.body().a())) + " MeV");
        return new Card("NOYAU " + name, "La charge +" + z + "e est répartie dans la sphère : dedans, le champ croît avec r (E ∝ r) ; "
                + "dehors, il décroît en 1/r².", lines, FusionRenderer.haloColor(z, 0), vectors);
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
        if (Math.abs(beta2) >= 1e-3) return String.format(Locale.FRANCE, "%.3f", beta2);
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
