package org.genesis.fusion;

import com.studio.genesis.grpc.CollisionBody;
import com.studio.genesis.grpc.CollisionRun;
import com.studio.genesis.grpc.CollisionSample;
import java.util.ArrayList;
import java.util.List;

/**
 * Le calcul découpé en étapes de lecture, chacune avec sa durée à l'écran. Pendant l'approche, la soudure et le rebond,
 * le temps physique défile linéairement : on voit le noyau arriver vite, freiner contre la répulsion, puis tomber dans
 * l'autre. Deux étapes n'ont pas d'échantillons : l'effet tunnel (pas de trajectoire classique sous la barrière) et,
 * pour les noyaux lourds dont la soudure calculée s'arrête au contact, la fonte des deux formes en une. Les produits
 * sont montrés tant qu'ils restent sur la carte. Rien ne déplace les points calculés.
 */
public final class CollisionTimeline {
    enum Kind { SAMPLES, TUNNEL, MERGE }

    record Stage(String name, Kind kind, int first, int last, double seconds, double start, double end) {
        double span() {
            return end - start;
        }
    }

    /** Où l'on est : l'étape, la progression dans l'étape (0..1) et le temps physique à montrer. */
    record At(Stage stage, double local, double time) {}

    private final CollisionRun run;
    private final double[] times;
    private final List<Stage> stages = new ArrayList<>();
    private final double duration;
    private final int lastApproach;
    private final boolean tunnels;

    CollisionTimeline(CollisionRun run) {
        this.run = run;
        int n = run.getSamplesCount();
        if (n < 2) throw new IllegalArgumentException("A collision needs at least two samples");
        times = new double[n];
        for (int i = 0; i < n; i++) times[i] = run.getSamples(i).getTimeS();
        List<int[]> runs = new ArrayList<>();
        int begin = 0;
        for (int i = 1; i <= n; i++) {
            if (i == n || !run.getSamples(i).getPhase().equals(run.getSamples(begin).getPhase())) {
                runs.add(new int[]{begin, i - 1});
                begin = i;
            }
        }
        int approachEnd = -1;
        for (int[] r : runs) if (phase(r[0]).equals("approach")) approachEnd = r[1];
        lastApproach = approachEnd;
        boolean reacted = false;
        for (int[] r : runs) {
            String p = phase(r[0]);
            if (r[0] > approachEnd && !p.equals("approach") && !p.equals("scattered")) reacted = true;
        }
        boolean touching = approachEnd >= 0 && separation(approachEnd) <= run.getContactFm() * 1.08;
        tunnels = reacted && approachEnd >= 0 && !touching;
        List<Stage> raw = new ArrayList<>();
        for (int k = 0; k < runs.size(); k++) {
            int[] r = runs.get(k);
            String p = phase(r[0]);
            String next = k + 1 < runs.size() ? phase(runs.get(k + 1)[0]) : "";
            int last = p.equals("products") ? productsOnMap(r[0], r[1]) : r[1];
            raw.add(new Stage(p, Kind.SAMPLES, r[0], last, seconds(p, r), 0, 0));
            if (p.equals("approach") && reacted && r[1] == approachEnd) {
                if (tunnels) raw.add(new Stage("tunnel", Kind.TUNNEL, r[1], r[1], 1.8, 0, 0));
                // Sans soudure calculée (anciens calculs) : la soudure est mise en scène.
                if (!next.equals("contact") && !next.equals("merge")) raw.add(new Stage("merge", Kind.MERGE, r[1], r[1], 1.6, 0, 0));
            }
            // La soudure calculée s'est arrêtée au contact (noyaux lourds) : les deux formes fondent en une.
            if (p.equals("merge") && !next.equals("contact") && separation(r[1]) > .3 * run.getContactFm())
                raw.add(new Stage("relax", Kind.MERGE, r[1], r[1], 1.6, 0, 0));
        }
        double total = 0;
        for (Stage s : raw) total += s.seconds();
        duration = total;
        double cursor = 0;
        for (Stage s : raw) {
            double start = cursor / total;
            cursor += s.seconds();
            stages.add(new Stage(s.name(), s.kind(), s.first(), s.last(), s.seconds(), start, cursor / total));
        }
    }

    private String phase(int i) {
        return run.getSamples(i).getPhase();
    }

    private double separation(int i) {
        CollisionSample s = run.getSamples(i);
        if (s.getBodiesCount() < 2) return 0;
        double d = 0;
        for (int k = 0; k < 3; k++) {
            double x = s.getBodies(0).getPosition(k) - s.getBodies(1).getPosition(k);
            d += x * x;
        }
        return Math.sqrt(d);
    }

    /** Durée à l'écran de chaque phase calculée (secondes). */
    private double seconds(String phase, int[] r) {
        return switch (phase) {
            case "approach" -> 3.2;
            case "merge" -> 2.4;
            case "scattered" -> 2.6;
            case "contact" -> 1.8;
            case "compound" -> 1.3;
            case "evaporation" -> Math.max(1.4, Math.min(4.5, .6 * emissions(r)));
            case "products" -> 4.2;
            default -> 1.5;
        };
    }

    /**
     * Le dernier échantillon des produits à montrer : celui où le plus rapide a parcouru 0,85 fois la taille de la
     * carte depuis le début de l'étape. Au-delà, ils ne feraient que sortir du cadre (jusqu'au centimètre).
     */
    private int productsOnMap(int first, int last) {
        double map = 0;
        CollisionSample start = run.getSamples(0);
        for (CollisionBody b : start.getBodiesList()) map = Math.max(map, norm(b));
        CollisionSample origin = run.getSamples(first);
        for (int i = first + 1; i <= last; i++) {
            CollisionSample s = run.getSamples(i);
            double travelled = 0;
            for (int b = 0; b < Math.min(s.getBodiesCount(), origin.getBodiesCount()); b++) {
                double d = 0;
                for (int c = 0; c < 3; c++) {
                    double x = s.getBodies(b).getPosition(c) - origin.getBodies(b).getPosition(c);
                    d += x * x;
                }
                travelled = Math.max(travelled, Math.sqrt(d));
            }
            if (map > 0 && travelled >= .85 * map) return i;
        }
        return last;
    }

    private static double norm(CollisionBody b) {
        double d = 0;
        for (int c = 0; c < Math.min(3, b.getPositionCount()); c++) d += b.getPosition(c) * b.getPosition(c);
        return Math.sqrt(d);
    }

    private int emissions(int[] r) {
        int count = 0;
        for (int i = r[0] + 1; i <= r[1]; i++)
            if (run.getSamples(i).getBodiesCount() > run.getSamples(i - 1).getBodiesCount()) count++;
        return Math.max(1, count);
    }

    double durationSeconds() {
        return duration;
    }

    List<Stage> stages() {
        return stages;
    }

    int lastApproach() {
        return lastApproach;
    }

    /** Le moteur a-t-il fait passer les noyaux sous la barrière (effet tunnel) ? */
    boolean tunnels() {
        return tunnels;
    }

    At at(double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        Stage stage = stages.get(stages.size() - 1);
        for (Stage s : stages) {
            if (f < s.end()) {
                stage = s;
                break;
            }
        }
        double local = stage.span() > 0 ? Math.max(0, Math.min(1, (f - stage.start()) / stage.span())) : 1;
        return new At(stage, local, physical(stage, local));
    }

    /**
     * Le ralenti de la rupture : pendant les produits, la lecture suit t = t₀ + (t₁ − t₀)·u^RUPTURE_EASE. Les premiers
     * instants, où les fragments se séparent, prennent la moitié du temps de lecture pour un vingtième du temps physique ;
     * la suite accélère. Le temps lu reste exact, et l'horloge affiche le ralenti en vigueur à chaque image.
     */
    static final double RUPTURE_EASE = 3.0;

    private double physical(Stage s, double local) {
        double t0 = times[s.first()], t1 = times[s.last()];
        if (s.kind() != Kind.SAMPLES || s.last() == s.first()) return t1;
        return switch (s.name()) {
            case "products" -> t0 + Math.pow(local, RUPTURE_EASE) * (t1 - t0);
            case "evaporation" -> {
                double x = s.first() + local * (s.last() - s.first());
                int i = (int) Math.floor(x);
                int j = Math.min(s.last(), i + 1);
                yield times[i] + (times[j] - times[i]) * (x - i);
            }
            default -> t0 + local * (t1 - t0);
        };
    }

    double timeAt(double fraction) {
        return at(fraction).time();
    }

    /** La fraction de lecture où le temps physique `time` est montré (dans les étapes calculées). */
    double fractionAt(double time) {
        for (int k = stages.size() - 1; k >= 0; k--) {
            Stage s = stages.get(k);
            if (s.kind() != Kind.SAMPLES) continue;
            double t0 = times[s.first()], t1 = times[s.last()];
            if (time < t0 || time > t1) continue;
            double local;
            if (t1 <= t0) local = 0;
            else if (s.name().equals("evaporation")) {
                int i = s.first();
                while (i < s.last() && times[i + 1] < time) i++;
                double frac = times[Math.min(i + 1, s.last())] > times[i]
                        ? (time - times[i]) / (times[Math.min(i + 1, s.last())] - times[i]) : 0;
                local = (i - s.first() + frac) / Math.max(1, s.last() - s.first());
            } else local = (time - t0) / (t1 - t0);
            if (s.name().equals("products")) local = Math.pow(Math.max(0, Math.min(1, local)), 1 / RUPTURE_EASE);
            return s.start() + Math.max(0, Math.min(1, local)) * s.span();
        }
        return time < times[0] ? 0 : 1;
    }

    double phaseStart(String phase) {
        for (Stage s : stages) if (s.name().equals(phase)) return s.start();
        return -1;
    }

    /** Le point le plus serré : la fin de l'approche. */
    double closestApproach() {
        for (Stage s : stages) if (s.name().equals("approach")) return s.end() - 1e-6;
        return 0;
    }

    /** L'instant de la soudure (−1 s'il n'y en a pas) : la fin de la soudure calculée, ou de sa mise en scène. */
    double flashFraction() {
        Stage weld = null;
        for (Stage s : stages) if (s.kind() == Kind.MERGE || s.name().equals("merge")) weld = s;
        return weld == null ? -1 : weld.kind() == Kind.MERGE ? weld.start() + .6 * weld.span() : weld.end();
    }

    /** La soudure vient-elle du calcul (échantillons du moteur) ? */
    boolean computedMerge() {
        for (Stage s : stages) if (s.kind() == Kind.SAMPLES && s.name().equals("merge")) return true;
        return false;
    }
}
