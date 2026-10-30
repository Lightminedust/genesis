package org.genesis.chain;

import org.genesis.app.I18n;
import org.genesis.common.LabCamera;
import org.genesis.common.Visuals;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * La scène 3D de la réaction en chaîne. La matière est un volume de noyaux : un point pour un nombre fixe de noyaux
 * réels (10²³ en général), si bien qu'une sphère comprimée garde ses points et les resserre, et qu'une sphère plus
 * grosse en a davantage. Les neutrons du tir volent le long des traces calculées par le moteur ; chaque choc tombe
 * sur un noyau du volume : une diffusion le fait tressaillir, une capture lâche une onde γ violette, une fission
 * l'éclate en deux fragments dans un éclair. Pendant l'excursion, la population calculée devient une tempête
 * d'étincelles réparties selon le flux, et la matière prend la couleur du corps noir à sa température.
 */
final class ChainScene {
    /** Un neutron : génération, parent, points (x, y, z en cm, t en ns), nature de chaque point, groupes, issue. */
    record Track(int generation, int parent, float[] points, int[] kinds, int[] groups, String end, int children) {
        int count() { return points.length / 4; }
        double birth() { return points[3]; }
        double death() { return points[points.length - 1]; }
    }

    /** Un choc sur un noyau du volume. */
    private record Hit(double time, double x, double y, double z, int kind, int nucleus, int track) {}

    static final int SCATTER = 1, SURFACE = 2, ESCAPE = 3, END = 4;
    static final int HIT_SCATTER = 0, HIT_FISSION = 1, HIT_CAPTURE = 2;
    static final double ATOMS_PER_POINT = 1e23;
    static final int MAX_POINTS = 1600;
    static final Color FAST = Color.web("#9BE7FF"), MIDDLE = Color.web("#86EFAC"), SLOW = Color.web("#FBBF24");
    static final Color FLASH = Color.web("#FFD27A"), GAMMA = Color.web("#B48CFF"), FRAGMENT = Color.web("#FF9F5A");
    final LabCamera camera = new LabCamera();
    final List<Track> tracks = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    double[] bounds = new double[0];
    double coreRadius = 7, outerRadius = 7, criticalRadius, keff = 0;
    Color metal = Color.web("#8E96A3"), reflectorTint = Color.web("#9FB7D9");
    boolean reflected;
    /** Le temps de la cascade (ns) ; NaN : pas de cascade à montrer. */
    double timeNs = Double.NaN, endNs;
    /** Combien de nanosecondes simulées par seconde réelle : la durée des éclairs suit le ralenti. */
    double nsPerSecond = 5;
    double temperature = 293, radiusFactor = 1;
    /** Le geste en cours : la sphère montrée plus grande ou plus petite que celle du moteur, avant de lâcher. */
    double previewScale = 1, previewOuter = Double.NaN;
    /** Survol du bord (0–1), et le réflecteur qu'on est en train de poser (null : aucun). */
    double rimHover;
    Color reflectorPreview;
    /** La tempête de l'excursion : intensité 0–1 et profil radial du flux. */
    double storm;
    double[] fluxRadius = new double[0], fluxProfile = new double[0];
    /** Le neutron suivi : sa lignée est mise en avant (−1 : aucun). */
    int selected = -1;
    private final Set<Integer> lineage = new HashSet<>();
    private float[] lattice = new float[0];
    private double[] fissionTime = new double[0];
    private double spacing = .2;
    private double atomsPerPoint = ATOMS_PER_POINT;
    private final Random random = new Random(11);
    private final List<double[]> sparks = new ArrayList<>();
    private double realClock;

    ChainScene() {
        camera.zoom = Math.log10(32);
        camera.pitch = 0.32;
        camera.yaw = -0.6;
        camera.fovHalfDeg = 20;
    }

    /** Les noyaux du volume : un point pour `atomsPerPoint` noyaux réels. */
    void setNuclei(double atoms) {
        int decade = 23;
        while (atoms / Math.pow(10, decade) > MAX_POINTS) decade++;
        atomsPerPoint = Math.pow(10, decade);
        int count = (int) Math.max(24, Math.min(MAX_POINTS, Math.round(atoms / atomsPerPoint)));
        if (count == lattice.length / 3) return;
        lattice = jitteredLattice(count, 20261025L);
        spacing = Math.cbrt(4 * Math.PI / 3 / count);
        fissionTime = new double[count];
        Arrays.fill(fissionTime, Double.POSITIVE_INFINITY);
        mapHits();
    }

    double atomsPerPoint() {
        return atomsPerPoint;
    }

    int nucleusCount() {
        return lattice.length / 3;
    }

    /** Des points sur un réseau cubique légèrement désordonné, dans la sphère unité. */
    private static float[] jitteredLattice(int count, long seed) {
        Random rng = new Random(seed);
        double a = Math.cbrt(4 * Math.PI / 3 / count);
        List<double[]> points = new ArrayList<>();
        int n = (int) Math.ceil(1 / a) + 1;
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                for (int k = -n; k <= n; k++) {
                    double x = (i + .5 * (rng.nextDouble() - .5)) * a, y = (j + .5 * (rng.nextDouble() - .5)) * a;
                    double z = (k + .5 * (rng.nextDouble() - .5)) * a;
                    if (x * x + y * y + z * z <= 1) points.add(new double[]{x, y, z});
                }
            }
        }
        java.util.Collections.shuffle(points, rng);
        while (points.size() < count) {
            double x, y, z;
            do {
                x = 2 * rng.nextDouble() - 1;
                y = 2 * rng.nextDouble() - 1;
                z = 2 * rng.nextDouble() - 1;
            } while (x * x + y * y + z * z > 1);
            points.add(new double[]{x, y, z});
        }
        float[] out = new float[3 * count];
        for (int i = 0; i < count; i++) {
            out[3 * i] = (float) points.get(i)[0];
            out[3 * i + 1] = (float) points.get(i)[1];
            out[3 * i + 2] = (float) points.get(i)[2];
        }
        return out;
    }

    void setTracks(List<Track> list) {
        tracks.clear();
        tracks.addAll(list);
        endNs = 0;
        for (Track t : tracks) endNs = Math.max(endNs, t.death());
        selected = -1;
        lineage.clear();
        hits.clear();
        for (int ti = 0; ti < tracks.size(); ti++) {
            Track t = tracks.get(ti);
            float[] p = t.points();
            for (int i = 1; i < t.count(); i++) {
                int kind = i < t.kinds().length ? t.kinds()[i] : 0;
                if (kind != SCATTER && kind != END) continue;
                int hit = kind == SCATTER ? HIT_SCATTER : "fission".equals(t.end()) ? HIT_FISSION
                        : "capture".equals(t.end()) ? HIT_CAPTURE : -1;
                if (hit < 0) continue;
                hits.add(new Hit(p[4 * i + 3], p[4 * i], p[4 * i + 1], p[4 * i + 2], hit, -1, ti));
            }
        }
        mapHits();
    }

    /** Chaque choc dans le cœur tombe sur le noyau le plus proche du volume ; une fission le consomme. */
    private void mapHits() {
        Arrays.fill(fissionTime, Double.POSITIVE_INFINITY);
        int n = lattice.length / 3;
        for (int h = 0; h < hits.size(); h++) {
            Hit hit = hits.get(h);
            double r = coreRadius;
            if (n == 0 || Math.sqrt(hit.x() * hit.x() + hit.y() * hit.y() + hit.z() * hit.z()) > r * 1.001) continue;
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double dx = lattice[3 * i] * r - hit.x(), dy = lattice[3 * i + 1] * r - hit.y(), dz = lattice[3 * i + 2] * r - hit.z();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            hits.set(h, new Hit(hit.time(), hit.x(), hit.y(), hit.z(), hit.kind(), best, hit.track()));
            if (hit.kind() == HIT_FISSION && best >= 0) fissionTime[best] = Math.min(fissionTime[best], hit.time());
        }
    }

    /** Le neutron suivi et toute sa descendance. */
    void select(int track) {
        selected = track;
        lineage.clear();
        if (track < 0) return;
        lineage.add(track);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < tracks.size(); i++) {
                if (!lineage.contains(i) && lineage.contains(tracks.get(i).parent())) {
                    lineage.add(i);
                    grew = true;
                }
            }
        }
        // et ses ancêtres, pour suivre le fil depuis le neutron d'amorçage
        int up = tracks.get(track).parent();
        while (up >= 0) {
            lineage.add(up);
            up = tracks.get(up).parent();
        }
    }

    Color groupColor(int group) {
        if (group < 0 || group + 1 >= bounds.length) return FAST;
        double energy = Math.sqrt(bounds[group] * bounds[group + 1]);
        if (energy >= 1e5) return FAST;
        if (energy >= 1) return MIDDLE;
        return SLOW;
    }

    double displayedRadius() {
        return coreRadius * radiusFactor * previewScale;
    }

    double displayedOuter() {
        if (!Double.isNaN(previewOuter)) return previewOuter;
        return reflected ? Math.max(outerRadius, displayedRadius()) : displayedRadius();
    }

    /** La taille de vue qui montre toute la sphère (réflecteur compris, ou le gaz qui s'étend), en cm. */
    double framing() {
        return 4.6 * Math.max(outerRadius, coreRadius * radiusFactor);
    }

    // ================================================================== le dessin
    /** Dessine la scène dans un canevas de w × h ; le centre de la sphère en (cx, cy). */
    void draw(GraphicsContext gc, double w, double h, double cx, double cy, double dt, boolean dragging) {
        realClock += dt;
        camera.tick(dt, dragging, Math.log10(Math.max(coreRadius, .5) * 1.2), Math.log10(Math.max(framing(), 1) * 12));
        camera.setup(w, h, cx, cy, camera.distance());
        gc.setFill(new RadialGradient(0, 0, cx, cy, Math.max(w, h) * .7, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0B1320")), new Stop(1, Color.web("#03060B"))));
        gc.fillRect(0, 0, w, h);
        double[] center = camera.project(0, 0, 0);
        if (center == null) return;
        double scale = center[3];
        double radius = displayedRadius();
        double rPx = radius * scale;
        aura(gc, center[0], center[1], rPx);
        if (reflectorPreview != null) {
            shell(gc, center[0], center[1], Math.max(displayedOuter(), radius * 1.45) * scale, reflectorPreview, .28);
        } else if (reflected) {
            shell(gc, center[0], center[1], displayedOuter() * scale, reflectorTint, .2);
        }
        if (criticalRadius > 0) ghost(gc, center[0], center[1], criticalRadius * radiusFactor * scale);
        heatGlow(gc, center[0], center[1], rPx);
        envelope(gc, center[0], center[1], rPx);
        nuclei(gc, radius);
        if (!Double.isNaN(timeNs)) drawTracks(gc, center[2]);
        drawStorm(gc, dt, radius);
        rim(gc, center[0], center[1], rPx);
    }

    /** Une aura discrète qui dit k_eff sans un mot : bleu froid, ambre au critique, rouge au-delà. */
    private void aura(GraphicsContext gc, double x, double y, double r) {
        Color c;
        if (keff <= 0) c = Color.web("#4A5A70");
        else if (keff < 1) c = Color.web("#5AA9FF").interpolate(Color.web("#F5B84B"), Math.max(0, (keff - .7) / .3));
        else c = Color.web("#F5B84B").interpolate(Color.web("#FF5A3D"), Math.min(1, (keff - 1) / .15));
        double halo = r * 1.9;
        double pulse = keff > 1 ? .12 + .06 * Math.sin(realClock * 4) : .1;
        gc.setFill(new RadialGradient(0, 0, x, y, halo, false, CycleMethod.NO_CYCLE,
                new Stop(.45, c.deriveColor(0, 1, 1, 0)), new Stop(.55, c.deriveColor(0, 1, 1, pulse)),
                new Stop(1, Color.TRANSPARENT)));
        gc.fillOval(x - halo, y - halo, 2 * halo, 2 * halo);
    }

    private void heatGlow(GraphicsContext gc, double x, double y, double r) {
        Color light = Visuals.blackbody(temperature);
        double brightness = Math.max(light.getRed(), Math.max(light.getGreen(), light.getBlue()));
        if (brightness < .02) return;
        double halo = r * (1.6 + 1.4 * brightness);
        gc.setFill(new RadialGradient(0, 0, x, y, halo, false, CycleMethod.NO_CYCLE,
                new Stop(0, light.deriveColor(0, 1, 1, .6 * brightness)), new Stop(.55, light.deriveColor(0, 1, 1, .18 * brightness)),
                new Stop(1, Color.TRANSPARENT)));
        gc.fillOval(x - halo, y - halo, 2 * halo, 2 * halo);
    }

    /** L'enveloppe de la sphère : un verre à peine teinté, qui ne cache pas les noyaux. */
    private void envelope(GraphicsContext gc, double x, double y, double r) {
        if (r < .5) return;
        Color glow = Visuals.blackbody(temperature);
        double heat = Math.max(glow.getRed(), Math.max(glow.getGreen(), glow.getBlue()));
        Color tint = metal.interpolate(glow, Math.min(1, heat * 1.1));
        double alpha = radiusFactor > 1.05 ? .08 / Math.min(radiusFactor, 4) : .1 + .45 * heat;
        gc.setFill(new RadialGradient(0, 0, x - r * .3, y - r * .35, r * 1.4, false, CycleMethod.NO_CYCLE,
                new Stop(0, tint.interpolate(Color.WHITE, .4).deriveColor(0, 1, 1, alpha)),
                new Stop(1, tint.interpolate(Color.BLACK, .5).deriveColor(0, 1, 1, alpha * .6))));
        gc.fillOval(x - r, y - r, 2 * r, 2 * r);
        gc.setLineWidth(1.1);
        gc.setStroke(tint.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, radiusFactor > 1.05 ? .12 : .4));
        gc.strokeOval(x - r, y - r, 2 * r, 2 * r);
    }

    /** Le volume de noyaux, du plus lointain au plus proche ; leur couleur suit la température. */
    private void nuclei(GraphicsContext gc, double radius) {
        int n = lattice.length / 3;
        if (n == 0) return;
        double now = Double.isNaN(timeNs) ? Double.NEGATIVE_INFINITY : timeNs;
        double[] sx = new double[n], sy = new double[n], size = new double[n], depth = new double[n];
        long[] order = new long[n];
        int shown = 0;
        double nucleus = .12 * spacing * radius;
        // Gaz : les noyaux s'écartent avec la sphère et s'éclaircissent.
        double expansion = Math.max(1, radiusFactor);
        double near = Double.MAX_VALUE, far = 0;
        for (int i = 0; i < n; i++) {
            if (fissionTime[i] <= now) continue;
            double[] p = camera.project(lattice[3 * i] * radius, lattice[3 * i + 1] * radius, lattice[3 * i + 2] * radius);
            if (p == null) continue;
            sx[i] = p[0];
            sy[i] = p[1];
            depth[i] = p[2];
            near = Math.min(near, p[2]);
            far = Math.max(far, p[2]);
            size[i] = Math.max(1.1, nucleus * p[3] / Math.sqrt(expansion));
            long key = (long) Math.max(0, Math.min(1e9, (1e6 - p[2]) * 1e3));     // le plus lointain d'abord
            order[shown++] = (key << 20) | i;
        }
        Arrays.sort(order, 0, shown);
        Color glow = Visuals.blackbody(temperature);
        double heat = Math.max(glow.getRed(), Math.max(glow.getGreen(), glow.getBlue()));
        Color base = metal.interpolate(glow.interpolate(Color.WHITE, .25), Math.min(1, heat * 1.2));
        double alpha = (radiusFactor > 1.05 ? Math.max(.25, 1 / Math.pow(radiusFactor, 1.5)) : 1) * .78;
        double span = Math.max(1e-9, far - near);
        for (int k = 0; k < shown; k++) {
            int i = (int) (order[k] & 0xFFFFF);
            double s = size[i];
            double shade = .5 + .5 * (far - depth[i]) / span;
            gc.setFill(base.deriveColor(0, 1, shade, alpha));
            gc.fillOval(sx[i] - s, sy[i] - s, 2 * s, 2 * s);
            if (s > 2.2) {
                gc.setFill(Color.color(1, 1, 1, .32 * alpha * shade));
                gc.fillOval(sx[i] - s * .45, sy[i] - s * .5, s * .6, s * .6);
            }
        }
    }

    private static void shell(GraphicsContext gc, double x, double y, double r, Color base, double alpha) {
        if (r < .5) return;
        Color light = base.interpolate(Color.WHITE, .35), dark = base.interpolate(Color.BLACK, .5);
        gc.setFill(new RadialGradient(0, 0, x - r * .35, y - r * .4, r * 1.45, false, CycleMethod.NO_CYCLE,
                new Stop(0, light.deriveColor(0, 1, 1, alpha)), new Stop(.5, base.deriveColor(0, 1, 1, alpha * .7)),
                new Stop(1, dark.deriveColor(0, 1, 1, alpha))));
        gc.fillOval(x - r, y - r, 2 * r, 2 * r);
        gc.setLineWidth(1.4);
        gc.setStroke(base.interpolate(Color.WHITE, .5).deriveColor(0, 1, 1, Math.min(1, alpha * 3)));
        gc.strokeOval(x - r, y - r, 2 * r, 2 * r);
    }

    private static void ghost(GraphicsContext gc, double x, double y, double r) {
        gc.setStroke(Color.web("#F5B84B", .5));
        gc.setLineWidth(1.2);
        gc.setLineDashes(6, 5);
        gc.strokeOval(x - r, y - r, 2 * r, 2 * r);
        gc.setLineDashes(null);
        gc.setFont(Font.font("Segoe UI Semibold", 10));
        gc.setFill(Color.web("#F5B84B", .8));
        gc.setTextAlign(TextAlignment.LEFT);
        I18n.fillText(gc, "rayon critique", x + r * .72 + 6, y - r * .72 - 4);
    }

    /** Le bord qu'on attrape : il s'allume au survol. */
    private void rim(GraphicsContext gc, double x, double y, double r) {
        if (rimHover <= 0.01 || r < 2) return;
        gc.setStroke(Color.web("#8FE6FF", .25 + .55 * rimHover));
        gc.setLineWidth(2 + 2 * rimHover);
        gc.strokeOval(x - r, y - r, 2 * r, 2 * r);
        double a = -Math.PI / 4;
        double ax = x + r * Math.cos(a), ay = y + r * Math.sin(a);
        gc.setFill(Color.web("#8FE6FF", .9 * rimHover));
        gc.fillPolygon(new double[]{ax + 10, ax + 18, ax + 13}, new double[]{ay - 10, ay - 2, ay - 15}, 3);
        gc.fillPolygon(new double[]{ax - 10, ax - 18, ax - 13}, new double[]{ay + 10, ay + 2, ay + 15}, 3);
    }

    private void drawTracks(GraphicsContext gc, double centerDepth) {
        double now = timeNs;
        double lasting = 0.7 * nsPerSecond;        // un éclair dure 0,7 s réelle, quel que soit le ralenti
        gc.setLineCap(StrokeLineCap.ROUND);
        boolean following = selected >= 0;
        for (int ti = 0; ti < tracks.size(); ti++) {
            Track t = tracks.get(ti);
            if (now < t.birth()) continue;
            double dim = following && !lineage.contains(ti) ? .18 : 1;
            float[] p = t.points();
            int n = t.count();
            double last = t.death();
            double[] previous = camera.project(p[0], p[1], p[2]);
            double headX = Double.NaN, headY = Double.NaN, headDepth = 0;
            Color headColor = FAST;
            for (int i = 1; i < n && previous != null; i++) {
                double t0 = p[4 * (i - 1) + 3], t1 = p[4 * i + 3];
                if (now <= t0) break;
                double f = t1 > t0 ? Math.min(1, (now - t0) / (t1 - t0)) : 1;
                double x = p[4 * (i - 1)] + f * (p[4 * i] - p[4 * (i - 1)]);
                double y = p[4 * (i - 1) + 1] + f * (p[4 * i + 1] - p[4 * (i - 1) + 1]);
                double z = p[4 * (i - 1) + 2] + f * (p[4 * i + 2] - p[4 * (i - 1) + 2]);
                double[] here = camera.project(x, y, z);
                if (here == null) break;
                Color c = groupColor(t.groups().length >= i ? t.groups()[i - 1] : 0);
                double fade = now > last ? Math.max(0, 1 - (now - last) / (lasting * 3)) : 1;
                double behind = here[2] > centerDepth ? .6 : 1;
                gc.setStroke(c.deriveColor(0, 1, 1, .65 * fade * behind * dim));
                gc.setLineWidth(following && dim == 1 ? 2 : 1.3);
                gc.strokeLine(previous[0], previous[1], here[0], here[1]);
                previous = here;
                headX = here[0];
                headY = here[1];
                headDepth = here[2];
                headColor = c;
                if (now < t1) break;
            }
            if (Double.isNaN(headX) || now >= last) continue;
            double behind = headDepth > centerDepth ? .65 : 1;
            gc.setFill(headColor.deriveColor(0, 1, 1, .35 * behind * dim));
            gc.fillOval(headX - 7, headY - 7, 14, 14);
            gc.setFill(Color.WHITE.interpolate(headColor, .3).deriveColor(0, 1, 1, behind * dim));
            gc.fillOval(headX - 2.8, headY - 2.8, 5.6, 5.6);
        }
        for (Hit hit : hits) {
            double age = (now - hit.time()) / lasting;
            if (age < 0 || age > 1) continue;
            double dim = following && !lineage.contains(hit.track()) ? .2 : 1;
            double[] p = camera.project(hit.x(), hit.y(), hit.z());
            if (p == null) continue;
            switch (hit.kind()) {
                case HIT_FISSION -> fission(gc, hit, p, age, dim);
                case HIT_CAPTURE -> {
                    double r = 3 + 26 * age;
                    gc.setStroke(GAMMA.deriveColor(0, 1, 1, (1 - age) * dim));
                    gc.setLineWidth(1.5);
                    gc.strokeOval(p[0] - r, p[1] - r, 2 * r, 2 * r);
                    gc.setFill(GAMMA.deriveColor(0, 1, 1, .9 * (1 - age) * dim));
                    gc.fillOval(p[0] - 3, p[1] - 3, 6, 6);
                }
                default -> {
                    // Une diffusion : le noyau touché tressaille, une étincelle brève au point du choc.
                    double s = 4 * (1 - age);
                    gc.setStroke(Color.color(1, 1, 1, .7 * (1 - age) * dim));
                    gc.setLineWidth(1);
                    gc.strokeLine(p[0] - s, p[1], p[0] + s, p[1]);
                    gc.strokeLine(p[0], p[1] - s, p[0], p[1] + s);
                }
            }
        }
    }

    /** L'éclair d'une fission : un anneau qui s'étend, et le noyau qui s'ouvre en deux fragments. */
    private void fission(GraphicsContext gc, Hit hit, double[] p, double age, double dim) {
        double r = 4 + 34 * age;
        gc.setStroke(FLASH.deriveColor(0, 1, 1, (1 - age) * dim));
        gc.setLineWidth(1.8);
        gc.strokeOval(p[0] - r, p[1] - r, 2 * r, 2 * r);
        double core = 7 * (1 - age) + 1;
        gc.setFill(Color.WHITE.interpolate(FLASH, age).deriveColor(0, 1, 1, (1 - age) * dim));
        gc.fillOval(p[0] - core, p[1] - core, 2 * core, 2 * core);
        // Deux fragments repartent dos à dos (leur direction est tirée pour l'image : le moteur ne la calcule pas).
        Random rng = new Random(Double.doubleToLongBits(hit.time()) ^ hit.track());
        double ux = rng.nextGaussian(), uy = rng.nextGaussian(), uz = rng.nextGaussian();
        double norm = Math.sqrt(ux * ux + uy * uy + uz * uz);
        double travel = spacing * coreRadius * 1.2 * Math.sqrt(age);
        for (int side = -1; side <= 1; side += 2) {
            double[] q = camera.project(hit.x() + side * ux / norm * travel, hit.y() + side * uy / norm * travel,
                    hit.z() + side * uz / norm * travel);
            if (q == null) continue;
            double s = Math.max(1.5, .12 * spacing * coreRadius * q[3]) * (1 - .5 * age);
            gc.setFill(FRAGMENT.deriveColor(0, 1, 1, (1 - age) * dim));
            gc.fillOval(q[0] - s, q[1] - s, 2 * s, 2 * s);
        }
    }

    /** La tempête : des étincelles nées selon le flux calculé, d'autant plus nombreuses que la population est grande. */
    private void drawStorm(GraphicsContext gc, double dt, double radius) {
        int wanted = (int) Math.round(Math.max(0, storm) * 700);
        for (int i = 0; i < Math.min(wanted / 6 + 1, 120) && sparks.size() < wanted; i++) {
            double r = sampleRadius() * radius;
            double ux = random.nextGaussian(), uy = random.nextGaussian(), uz = random.nextGaussian();
            double norm = Math.sqrt(ux * ux + uy * uy + uz * uz);
            double vx = random.nextGaussian(), vy = random.nextGaussian(), vz = random.nextGaussian();
            double speed = radius * (.4 + random.nextDouble());
            double vn = Math.sqrt(vx * vx + vy * vy + vz * vz);
            sparks.add(new double[]{ux / norm * r, uy / norm * r, uz / norm * r, vx / vn * speed, vy / vn * speed,
                    vz / vn * speed, 0, .25 + .35 * random.nextDouble(), random.nextDouble() < .12 ? 1 : 0});
        }
        gc.setLineCap(StrokeLineCap.ROUND);
        for (int i = sparks.size() - 1; i >= 0; i--) {
            double[] s = sparks.get(i);
            s[6] += dt;
            if (s[6] > s[7] || storm <= 0) {
                sparks.remove(i);
                continue;
            }
            double age = s[6] / s[7];
            double x0 = s[0], y0 = s[1], z0 = s[2];
            s[0] += s[3] * dt;
            s[1] += s[4] * dt;
            s[2] += s[5] * dt;
            double[] a = camera.project(x0, y0, z0), b = camera.project(s[0], s[1], s[2]);
            if (a == null || b == null) continue;
            if (s[8] > 0) {
                double r = 3 + 14 * age;
                gc.setStroke(FLASH.deriveColor(0, 1, 1, .8 * (1 - age)));
                gc.setLineWidth(1.2);
                gc.strokeOval(b[0] - r, b[1] - r, 2 * r, 2 * r);
            } else {
                gc.setStroke(Color.WHITE.interpolate(FAST, .4).deriveColor(0, 1, 1, .85 * (1 - age)));
                gc.setLineWidth(1.4);
                gc.strokeLine(a[0], a[1], b[0], b[1]);
            }
        }
    }

    /** Un rayon tiré selon le flux calculé (r² φ(r)), en fraction du rayon du cœur. */
    private double sampleRadius() {
        if (fluxRadius.length < 2 || fluxProfile.length != fluxRadius.length) return Math.cbrt(random.nextDouble());
        double total = 0;
        double[] cumulative = new double[fluxRadius.length];
        double inner = 0;
        int last = 0;
        for (int i = 0; i < fluxRadius.length; i++) {
            if (fluxRadius[i] > coreRadius * 1.0001) break;
            double shell = Math.pow(fluxRadius[i], 3) - Math.pow(inner, 3);
            total += Math.max(0, fluxProfile[i]) * shell;
            cumulative[i] = total;
            inner = fluxRadius[i];
            last = i;
        }
        if (total <= 0) return Math.cbrt(random.nextDouble());
        double u = random.nextDouble() * total;
        inner = 0;
        for (int i = 0; i <= last; i++) {
            if (cumulative[i] >= u) {
                double r = Math.cbrt(Math.pow(inner, 3) + random.nextDouble() * (Math.pow(fluxRadius[i], 3) - Math.pow(inner, 3)));
                return Math.min(1, r / coreRadius);
            }
            inner = fluxRadius[i];
        }
        return 1;
    }

    // ================================================================== ce que la souris touche
    /** Distances (px) du point d'écran au bord du cœur et au bord du réflecteur ; puis sa distance au centre (cm). */
    double[] rimDistance(double sx, double sy) {
        double[] c = camera.project(0, 0, 0);
        if (c == null) return new double[]{Double.MAX_VALUE, Double.MAX_VALUE, 0};
        double d = Math.hypot(sx - c[0], sy - c[1]);
        double r = displayedRadius() * c[3];
        double outer = reflected ? displayedOuter() * c[3] : Double.NaN;
        return new double[]{Math.abs(d - r), reflected ? Math.abs(d - outer) : Double.MAX_VALUE, d / c[3]};
    }

    /** Le point du cœur (cm) sous le curseur, un peu sous la surface ; null hors de la sphère. */
    double[] pointUnder(double sx, double sy) {
        double[] ray = camera.ray(sx, sy);
        double r = displayedRadius();
        double b = ray[0] * ray[3] + ray[1] * ray[4] + ray[2] * ray[5];
        double c = ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2] - r * r;
        double disc = b * b - c;
        if (disc < 0) return null;
        double near = -b - Math.sqrt(disc), far = -b + Math.sqrt(disc);
        double t = near + .3 * (far - near);
        double shrink = 1 / (previewScale * radiusFactor);
        return new double[]{(ray[0] + t * ray[3]) * shrink, (ray[1] + t * ray[4]) * shrink, (ray[2] + t * ray[5]) * shrink};
    }

    /** Le neutron en vol le plus proche du point d'écran (−1 : aucun à moins de 14 px). */
    int neutronAt(double sx, double sy) {
        if (Double.isNaN(timeNs)) return -1;
        int best = -1;
        double bestD = 14 * 14;
        for (int ti = 0; ti < tracks.size(); ti++) {
            Track t = tracks.get(ti);
            if (timeNs < t.birth() || timeNs >= t.death()) continue;
            float[] p = t.points();
            for (int i = 1; i < t.count(); i++) {
                double t0 = p[4 * (i - 1) + 3], t1 = p[4 * i + 3];
                if (timeNs > t1) continue;
                double f = t1 > t0 ? (timeNs - t0) / (t1 - t0) : 1;
                double[] q = camera.project(p[4 * (i - 1)] + f * (p[4 * i] - p[4 * (i - 1)]),
                        p[4 * (i - 1) + 1] + f * (p[4 * i + 1] - p[4 * (i - 1) + 1]),
                        p[4 * (i - 1) + 2] + f * (p[4 * i + 2] - p[4 * (i - 1) + 2]));
                if (q != null) {
                    double d = (q[0] - sx) * (q[0] - sx) + (q[1] - sy) * (q[1] - sy);
                    if (d < bestD) {
                        bestD = d;
                        best = ti;
                    }
                }
                break;
            }
        }
        return best;
    }

    int generationAt(double nanoseconds) {
        int generation = -1;
        for (Track t : tracks) if (t.birth() <= nanoseconds) generation = Math.max(generation, t.generation());
        return generation;
    }
}
