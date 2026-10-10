package org.genesis;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import static org.genesis.ReactorView.fmt;
import static org.genesis.ReactorView.lengthText;
import static org.genesis.ReactorView.scientificShort;

/**
 * Le plasma autour du choc, à son échelle propre. À l'échelle de la scène (quelques centaines de femtomètres,
 * ~10⁻²¹ s), le plasma est vide : l'ion voisin le plus proche est à n^(−1/3) ≈ 10⁸ fm, et une oscillation plasma dure
 * 10¹⁰ fois la collision. Ce médaillon le montre là où il vit : une boîte de quatre longueurs de Debye, dont toutes les
 * grandeurs viennent de la température T et de la densité n du panneau Lawson, de la composition du couple et du
 * freinage calculé par le moteur :
 * <ul>
 * <li>longueur de Debye λ_D = √(ε₀kT / e²(n_e + Σn_iZ_i²)) — électrons et ions à la même température ;</li>
 * <li>agitation thermique : vitesses tirées de la loi de Maxwell–Boltzmann (σ = √(kT/m) par axe, ramenée sous c), ions
 *     et électrons à leur propre masse : à la même énergie, les électrons vont √(m_i/m_e) fois plus vite — chaque
 *     particule traîne derrière elle la distance v·Δt qu'elle parcourt pendant une pose de 1/24 s de lecture, et sa
 *     couleur suit sa propre énergie cinétique ½mv² (0,1 → 100 keV, du bleu au blanc) ;</li>
 * <li>onde de Langmuir : pulsation de Bohm–Gross ω² = ω_p² + 3k²kT/m_e, ω_p = √(n_e e²/ε₀m_e), k = 2π/L ; les
 *     électrons s'y déplacent de ξ = (δn/n)/k, pour une perturbation de densité choisie (δn/n = 30 %) : le nuage
 *     respire, et là où il se resserre il s'éclaire ;</li>
 * <li>freinage (bremsstrahlung) : photons émis dans la boîte au taux P_br·L³/kT (P_br : la puissance du moteur), tirés
 *     au hasard (Poisson) ; chaque éclat naît sur un électron.</li>
 * </ul>
 * Une grille et une règle, à l'échelle physique, donnent la taille de la boîte : elles changent d'échelle avec la densité.
 * Le temps du médaillon : 1 s de lecture = un tiers de période plasma (2π/ω_p).
 */
final class PlasmaInset {
    static final double EPS0 = 8.8541878128e-12, E = 1.602176634e-19, ME = 9.1093837015e-31, AMU = 1.66053906660e-27, C = 299792458;
    /** ħ (J·s) et e²/4πε₀ (J·m). */
    static final double HBAR = 1.054571817e-34, KE2 = 2.3070775e-28;
    static final double WIDTH = 276, BOX_W = WIDTH - 28;

    /** La hauteur du médaillon ; `compact` : une boîte plus basse quand la colonne manque de place. */
    static double height(boolean compact) {
        return compact ? 190 : 236;
    }

    /**
     * La couleur d'une particule selon son énergie cinétique : 0,1 keV (bleu profond) → 100 keV (blanc), en échelle
     * logarithmique sur trois décades. Ions et électrons d'un même plasma ont la même énergie (équipartition) : c'est
     * leur vitesse qui diffère.
     */
    static Color energyColor(double energyKev) {
        return FusionRenderer.heatColor(Math.max(0, Math.min(1, (Math.log10(Math.max(energyKev, 1e-9)) + 1) / 3)));
    }

    /** Le milieu : T (keV), n (ions/m³), freinage (W/m³), les deux espèces (Z, A, couleur). */
    record Medium(double temperatureKev, double densityM3, double bremsWM3, int zA, int aA, int zB, int aB, Color colorA, Color colorB) {}

    private static final class Particle {
        double x, y, vx, vy;
        /** Son énergie cinétique (keV), des trois composantes tirées : ce qui donne sa couleur. */
        double energyKev;
        int kind;
    }

    /** La perturbation de densité de l'onde de Langmuir montrée : ξ = (δn/n)/k. Une amplitude choisie, pas calculée. */
    static final double WAVE_DENSITY = .3;
    /**
     * La pose d'une traînée : 1/24 s de lecture au plus, raccourcie pour que la particule la plus rapide ne barre pas
     * plus d'un dixième de la boîte — sinon les électrons, √(m_i/m_e) fois plus véloces, la zèbrent de part en part.
     */
    static final double EXPOSURE = 1 / 24.0, LONGEST_TRAIL = .1;

    private final List<Particle> particles = new ArrayList<>();
    private final List<double[]> flashes = new ArrayList<>();
    private final Random random = new Random(29);
    private Medium medium;
    private double clock, ions, electrons, photonDensityRate;
    private int ionCount, electronCount;
    /**
     * Longueur de Debye (m), largeur de la boîte (m, = 4 λ_D, ou 4a en plasma fortement couplé ; hauteur et profondeur à l'avenant), fréquence plasma (Hz),
     * secondes physiques par seconde de lecture.
     */
    double debye, box, boxHeight, plasmaFrequency, secondsPerSecond;
    /** Vitesses thermiques les plus probables (m/s), photons de freinage par seconde de lecture, nombre de Debye N_D. */
    double ionSpeed, electronSpeed, photonsPerSecond, debyeNumber, electronDensity;
    /**
     * Rayon de la sphère ionique a = (3/4πn_i)^⅓ (m), et longueur d'écrantage appliquée aux champs : λ_D, mais jamais
     * moins que a — quand N_D < 1 (plasma fortement couplé), l'écran de Debye–Hückel n'a plus de sens sous a.
     */
    double spacing, screening;

    /** La longueur de Debye (m) d'un plasma moitié A, moitié B (pur si A = B), électrons et ions à kT. */
    static double debyeLength(double temperatureKev, double densityM3, int zA, int zB, boolean same) {
        double na = same ? densityM3 : densityM3 / 2, nb = same ? 0 : densityM3 / 2;
        double ne = na * zA + nb * zB, z2 = na * zA * zA + nb * zB * zB, kt = temperatureKev * 1e3 * E;
        return Math.sqrt(EPS0 * kt / (E * E * (ne + z2)));
    }

    /** La fréquence plasma électronique f_p = ω_p/2π (Hz). */
    static double plasmaFrequency(double electronDensityM3) {
        return Math.sqrt(electronDensityM3 * E * E / (EPS0 * ME)) / (2 * Math.PI);
    }

    /** La vitesse la plus probable √(2kT/m), ramenée sous c (vitesse propre γv → v). */
    static double thermalSpeed(double temperatureKev, double massKg) {
        double u = Math.sqrt(2 * temperatureKev * 1e3 * E / massKg);
        return u / Math.sqrt(1 + u * u / (C * C));
    }

    /** Le renforcement de Salpeter (écrantage faible) du taux de fusion : f = exp(Z₁Z₂e²/(4πε₀λ_D kT)). */
    static double salpeter(int zA, int zB, double debyeM, double temperatureKev) {
        // e²/4πε₀ = 1,43996 MeV·fm = 1,43996·10⁻⁹ eV·m ; kT en eV.
        return Math.exp(zA * zB * 1.43996e-9 / debyeM / (temperatureKev * 1e3));
    }

    /** Le milieu change : grandeurs recalculées ; un chauffage met les vitesses à l'échelle √(T′/T), sans tout retirer. */
    void setMedium(Medium m) {
        if (m == null) return;
        boolean species = medium == null || medium.zA() != m.zA() || medium.aA() != m.aA() || medium.zB() != m.zB() || medium.aB() != m.aB();
        double scale = medium != null && !species && medium.temperatureKev() > 0 ? Math.sqrt(m.temperatureKev() / medium.temperatureKev()) : 1;
        boolean same = m.zA() == m.zB() && m.aA() == m.aB();
        debye = debyeLength(m.temperatureKev(), m.densityM3(), m.zA(), m.zB(), same);
        double na = same ? m.densityM3() : m.densityM3() / 2, nb = same ? 0 : m.densityM3() / 2;
        electronDensity = na * m.zA() + nb * m.zB();
        spacing = Math.cbrt(3 / (4 * Math.PI * m.densityM3()));
        screening = Math.max(debye, spacing);
        box = 4 * screening;
        plasmaFrequency = plasmaFrequency(electronDensity);
        secondsPerSecond = 1 / (3 * plasmaFrequency);
        double kt = m.temperatureKev() * 1e3 * E;
        ionSpeed = thermalSpeed(m.temperatureKev(), m.aA() * AMU);
        electronSpeed = thermalSpeed(m.temperatureKev(), ME);
        // Photons de freinage par m³ et par seconde de lecture (énergie moyenne d'un photon ≈ kT).
        photonDensityRate = m.bremsWM3() / kt * secondsPerSecond;
        debyeNumber = 4 / 3.0 * Math.PI * debye * debye * debye * (electronDensity + m.densityM3());
        // Autant de points que la boîte contient de particules, plafonné : un plasma fortement couplé n'en a presque pas.
        double full = box * box * (box * 118 / BOX_W);
        int wantedIons = (int) Math.min(46, Math.round(m.densityM3() * full));
        int wantedElectrons = (int) Math.min(150, wantedIons >= 46 ? Math.max(46, Math.round(46 * electronDensity / m.densityM3()))
                : Math.round(electronDensity * full));
        if (species || wantedIons != ionCount || wantedElectrons != electronCount) seed(m, same, wantedIons, wantedElectrons);
        else if (scale != 1) for (Particle p : particles) {
            p.vx *= scale;
            p.vy *= scale;
            p.energyKev *= scale * scale;
        }
        medium = m;
    }

    private void seed(Medium m, boolean same, int ionsWanted, int electronsWanted) {
        particles.clear();
        flashes.clear();
        ionCount = ionsWanted;
        electronCount = electronsWanted;
        for (int i = 0; i < ionCount + electronCount; i++) {
            Particle p = new Particle();
            p.kind = i >= ionCount ? 2 : same || i % 2 == 0 ? 0 : 1;
            p.x = random.nextDouble();
            p.y = random.nextDouble();
            double mass = p.kind == 2 ? ME : (p.kind == 0 ? m.aA() : m.aB()) * AMU;
            double sigma = Math.sqrt(m.temperatureKev() * 1e3 * E / mass);
            double ux = random.nextGaussian() * sigma, uy = random.nextGaussian() * sigma, uz = random.nextGaussian() * sigma;
            double gamma = Math.sqrt(1 + (ux * ux + uy * uy + uz * uz) / (C * C));
            p.vx = ux / gamma;
            p.vy = uy / gamma;
            p.energyKev = (gamma - 1) * mass * C * C / (1e3 * E);
            particles.add(p);
        }
    }

    /** Avance d'un pas de lecture `dt` (s) et dessine le médaillon en (x, y). */
    void draw(GraphicsContext gc, double x, double y, double dt, double sceneFm, boolean compact) {
        if (medium == null) return;
        double bh = compact ? 72 : 118, bw = BOX_W;
        // La boîte : 4 longueurs d'écran de large, la hauteur à l'échelle de l'image, autant de profondeur que de largeur.
        boxHeight = box * bh / bw;
        double volume = box * box * boxHeight;
        photonsPerSecond = photonDensityRate * volume;
        ions = medium.densityM3() * volume;
        electrons = electronDensity * volume;
        double step = Math.max(0, Math.min(.1, dt)) * secondsPerSecond;
        clock += step;
        for (Particle p : particles) {
            p.x = wrap(p.x + p.vx * step / box);
            p.y = wrap(p.y + p.vy * step / boxHeight);
        }
        // Le freinage : un tirage de Poisson du nombre de photons émis pendant ce pas, chacun sur un électron.
        double expected = photonsPerSecond * Math.max(0, Math.min(.1, dt));
        int emitted = poisson(expected);
        for (int k = 0; k < Math.min(emitted, 12) && electronCount > 0; k++) {
            Particle p = particles.get(ionCount + random.nextInt(electronCount));
            flashes.add(new double[]{p.x, p.y, 0});
        }
        gc.save();
        FusionDashboard.glass(gc, x, y, WIDTH, height(compact), null);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI Semibold", 9.5));
        gc.setFill(Color.web("#9FB6D2"));
        gc.fillText(FusionDashboard.spaced("LE PLASMA AUTOUR"), x + 14, y + 19);
        gc.setFont(Font.font("Segoe UI", 9.5));
        gc.setFill(Color.web("#7F98B8"));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(fmt(medium.temperatureKev()) + " keV · " + scientificShort(medium.densityM3()) + " ions/m³", x + WIDTH - 14, y + 19);
        // La boîte : quatre longueurs de Debye de côté.
        double bx = x + 14, by = y + 28;
        gc.setFill(Color.color(.01, .02, .05, .7));
        gc.fillRoundRect(bx, by, bw, bh, 8, 8);
        gc.save();
        gc.beginPath();
        gc.rect(bx, by, bw, bh);
        gc.clip();
        // La grille, à l'échelle physique : son pas change avec la densité, et la règle le nomme.
        double perMetre = bw / box, pitch = gridPitch(box / 4);
        gc.setLineWidth(1);
        gc.setStroke(Color.color(.6, .8, 1, .09));
        for (double gx = 0; gx <= box; gx += pitch) gc.strokeLine(bx + gx * perMetre, by, bx + gx * perMetre, by + bh);
        for (double gy = 0; gy <= boxHeight; gy += pitch) gc.strokeLine(bx, by + gy * perMetre, bx + bw, by + gy * perMetre);
        double k = 2 * Math.PI / box, omega = Math.sqrt(Math.pow(2 * Math.PI * plasmaFrequency, 2)
                + 3 * k * k * medium.temperatureKev() * 1e3 * E / ME);
        Particle test = null;
        double exposure = Math.min(EXPOSURE * secondsPerSecond, LONGEST_TRAIL * box / Math.max(1, Math.max(ionSpeed, electronSpeed)));
        for (Particle p : particles) {
            // La traînée : la distance v·Δt parcourue pendant la pose — les électrons en laissent √(m_i/m_e) fois plus.
            double tailX = p.vx * exposure / box * bw, tailY = p.vy * exposure / boxHeight * bh;
            double px = bx + p.x * bw, py = by + p.y * bh;
            Color c = energyColor(p.energyKev);
            if (p.kind == 2) {
                // L'onde de Langmuir : les électrons se déplacent ensemble de ξ = (δn/n)/k, à la pulsation de Bohm–Gross ;
                // là où ils se resserrent (∂ξ/∂x < 0), la densité monte et le nuage s'éclaire.
                double phase = 2 * Math.PI * p.x - omega * clock, shift = WAVE_DENSITY / (2 * Math.PI) * Math.sin(phase);
                double density = 1 - WAVE_DENSITY * Math.cos(phase);
                px = bx + wrap(p.x + shift) * bw;
                gc.setStroke(c.deriveColor(0, 1, 1, .22 * Math.min(1, density)));
                gc.setLineWidth(.8);
                gc.strokeLine(px - tailX, py - tailY, px, py);
                gc.setFill(c.interpolate(Color.WHITE, .3).deriveColor(0, 1, 1, Math.min(1, .5 * density)));
                gc.fillOval(px - .95, py - .95, 1.9, 1.9);
            } else {
                if (test == null && p.kind == 0) test = p;
                // L'ion garde la teinte de son isotope : elle le nomme ; son énergie l'éclaire.
                Color tint = (p.kind == 0 ? medium.colorA() : medium.colorB()).interpolate(c, .55);
                gc.setStroke(tint.deriveColor(0, 1, 1, .45));
                gc.setLineWidth(1.6);
                gc.strokeLine(px - tailX, py - tailY, px, py);
                gc.setFill(tint.deriveColor(0, 1, 1, .95));
                gc.fillOval(px - 2.3, py - 2.3, 4.6, 4.6);
            }
        }
        // La sphère de Debye d'un ion : au-delà, sa charge est écrantée par le nuage d'électrons.
        if (test != null) {
            double cx = bx + test.x * bw, cy = by + test.y * bh, rx = bw / 4;
            gc.setStroke(medium.colorA().interpolate(Color.WHITE, .4).deriveColor(0, 1, 1, .6));
            gc.setLineWidth(1);
            gc.setLineDashes(3, 4);
            gc.strokeOval(cx - rx, cy - rx, 2 * rx, 2 * rx);
            gc.setLineDashes(null);
            gc.setFont(Font.font("Segoe UI", 9));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(Color.color(.9, .95, 1, .8));
            gc.fillText(debye >= spacing ? "λ_D" : "a", cx + rx * .72, cy - rx * .72);
        }
        if (ionCount == 0) {
            gc.setFont(Font.font("Segoe UI", 9.5));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFill(Color.web("#9FB4CE"));
            gc.fillText("≈ " + scientificShort(ions) + " ion dans la boîte", bx + bw / 2, by + bh / 2, bw - 10);
        }
        Iterator<double[]> it = flashes.iterator();
        while (it.hasNext()) {
            double[] f = it.next();
            f[2] += Math.max(0, Math.min(.1, dt));
            if (f[2] > .35) {
                it.remove();
                continue;
            }
            double fx = bx + f[0] * bw, fy = by + f[1] * bh, r = 2 + 10 * f[2], a = 1 - f[2] / .35;
            gc.setFill(new RadialGradient(0, 0, fx, fy, r, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(.85, .93, 1, .9 * a)), new Stop(1, Color.color(.6, .8, 1, 0))));
            gc.fillOval(fx - r, fy - r, 2 * r, 2 * r);
        }
        // La règle : le pas de la grille, à l'échelle physique de la boîte.
        double ruler = pitch * perMetre;
        gc.setStroke(Color.color(.85, .92, 1, .75));
        gc.setLineWidth(1.1);
        gc.strokeLine(bx + 8, by + bh - 9, bx + 8 + ruler, by + bh - 9);
        gc.strokeLine(bx + 8, by + bh - 12, bx + 8, by + bh - 6);
        gc.strokeLine(bx + 8 + ruler, by + bh - 12, bx + 8 + ruler, by + bh - 6);
        gc.setFont(Font.font("Segoe UI", 9));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.color(.85, .92, 1, .85));
        gc.fillText(lengthText(pitch), bx + 12 + ruler, by + bh - 6);
        gc.restore();
        // Les chiffres : ce qui a été calculé, et ce qu'il représente.
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font("Segoe UI", 9.5));
        gc.setFill(Color.web("#CFE0F4"));
        double row = by + bh + 14;
        gc.fillText(debye >= spacing ? "largeur " + lengthText(box) + " = 4 λ_D · λ_D = " + lengthText(debye) + " · N_D = " + scientificShort(debyeNumber)
                : "largeur 4a = " + lengthText(box) + " · λ_D = " + lengthText(debye) + " < a : N_D = " + scientificShort(debyeNumber) + " < 1", bx, row, bw);
        gc.fillText("v_th : ions " + speed(ionSpeed) + " · électrons " + speed(electronSpeed) + " (√(m_i/m_e) fois plus vite)", bx, row + 13, bw);
        gc.fillText("f_p = " + frequency(plasmaFrequency) + " · onde : δn/n = " + fmt(100 * WAVE_DENSITY) + " % (choisi) · 1 s = "
                + scientificShort(secondsPerSecond) + " s", bx, row + 26, bw);
        gc.fillText("freinage " + power(medium.bremsWM3()) + " : " + rate(photonsPerSecond), bx, row + 39, bw);
        double r = Math.max(1, sceneFm) * 1e-15, ratio = r / screening;
        double coupling = medium.zA() * medium.zB() * 1.43996e-9 / screening / (medium.temperatureKev() * 1e3);
        gc.setFill(Color.web("#9FB4CE"));
        gc.fillText("à " + fmt(Math.max(1, sceneFm)) + " fm : e^(−r/λ) = " + (ratio < 1e-3 ? "1 − " + scientificShort(ratio) : fmt(Math.exp(-ratio)))
                + (coupling < 1 ? " · Salpeter f = " + factor(Math.exp(coupling)) : " · fortement couplé : Γ = Z₁Z₂e²/λkT = " + number(coupling)), bx, row + 52, bw);
        gc.setFill(Color.web("#6F86A3"));
        gc.fillText((ionCount > 0 ? "1 point ≈ " + number(ions / ionCount) + " ions, " + number(electrons / Math.max(1, electronCount))
                + " électrons" : "points : les particules réellement dans la boîte")
                + " · couleur : ½mv² · traînée : v·" + scientificShort(exposure) + " s", bx, row + 65, bw);
        gc.restore();
    }

    /**
     * Le pouvoir d'arrêt d'un ion (charge Z, vitesse v en m/s) sur les électrons libres d'un plasma (n_e en m⁻³), en J/m :
     * dE/dx = 4π n_e Z² (e²/4πε₀)² lnΛ / (m_e v²), avec le logarithme de Coulomb lnΛ = ln(λ_D / b_min) et
     * b_min = max(Ze²/4πε₀m_e v², ħ/2m_e v) (collision frontale classique ou longueur d'onde de de Broglie). Il croît
     * comme Z² et décroît comme 1/v² : un fragment lourd et lent ionise des milliers de fois plus qu'un proton rapide.
     * Un corps neutre n'ionise pas : 0.
     */
    static double stoppingPower(int z, double speed, double electronDensityM3, double debyeM) {
        if (z <= 0 || speed <= 0 || electronDensityM3 <= 0 || !(debyeM > 0)) return 0;
        double classic = z * KE2 / (ME * speed * speed), quantum = HBAR / (2 * ME * speed);
        double logarithm = Math.max(2, Math.log(debyeM / Math.max(classic, quantum)));
        return 4 * Math.PI * electronDensityM3 * z * z * KE2 * KE2 / (ME * speed * speed) * logarithm;
    }

    /** Le pas de grille le plus lisible sous `target` : 1, 2 ou 5 × une puissance de dix (en mètres). */
    static double gridPitch(double target) {
        double exp = Math.floor(Math.log10(Math.max(target, 1e-30))), base = Math.pow(10, exp), pitch = base;
        for (double v : new double[]{1, 2, 5}) if (v * base <= target) pitch = v * base;
        return pitch;
    }

    private static double wrap(double v) {
        return v - Math.floor(v);
    }

    private int poisson(double mean) {
        if (mean <= 0) return 0;
        if (mean > 30) return (int) Math.max(0, Math.round(mean + Math.sqrt(mean) * random.nextGaussian()));
        double limit = Math.exp(-mean), product = random.nextDouble();
        int count = 0;
        while (product > limit) {
            product *= random.nextDouble();
            count++;
        }
        return count;
    }

    private static String speed(double v) {
        return v >= .01 * C ? fmt(100 * v / C) + " % c" : scientificShort(v / 1000) + " km/s";
    }

    private static String frequency(double f) {
        if (f >= 1e15) return scientificShort(f) + " Hz";
        if (f >= 1e12) return fmt(f / 1e12) + " THz";
        if (f >= 1e9) return fmt(f / 1e9) + " GHz";
        if (f >= 1e6) return fmt(f / 1e6) + " MHz";
        return scientificShort(f) + " Hz";
    }

    private static String power(double w) {
        if (w >= 1e12) return scientificShort(w) + " W/m³";
        if (w >= 1e9) return fmt(w / 1e9) + " GW/m³";
        if (w >= 1e6) return fmt(w / 1e6) + " MW/m³";
        if (w >= 1e3) return fmt(w / 1e3) + " kW/m³";
        return scientificShort(w) + " W/m³";
    }

    /** Un taux de photons par seconde de lecture, lisible : « 3,2 photons/s » ou « 1 photon toutes les 31 min ». */
    private static String rate(double perSecond) {
        if (perSecond >= 1) return fmt(perSecond) + " photons/s";
        if (perSecond <= 0) return "aucun photon";
        return "1 photon toutes les " + ReactorView.duration(1 / perSecond);
    }

    private static String number(double v) {
        return v >= 1e4 ? scientificShort(v) : fmt(v);
    }

    private static String factor(double f) {
        double excess = f - 1;
        return Math.abs(excess) < 1e-3 ? "1 + " + scientificShort(excess) : fmt(f);
    }
}
