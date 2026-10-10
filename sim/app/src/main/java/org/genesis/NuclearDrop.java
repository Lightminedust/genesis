package org.genesis;

import com.studio.genesis.grpc.Nucleon;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Le noyau vu comme une goutte de liquide quantique, et non comme un tas de
 * billes figées.
 *
 * Vibrations de surface (Bohr et Mottelson). La surface est
 * R(θ, φ) = R₀ (1 + Σ α_λμ Y_λμ). Chaque mode λ est un oscillateur :
 *   - raideur C_λ = (λ−1)(λ+2)/(4π) · a_s A^(2/3) − 5(λ−1)/(2π(2λ+1)) · a_c Z(Z−1)/A^(1/3).
 *     La tension de surface résiste à la déformation ; la répulsion des
 *     protons l'aide (c'est elle qui mène à la fission quand C_λ < 0).
 *   - inertie (écoulement irrotationnel) B_λ = 3/(4πλ) · A m_N R₀².
 *   - ħω_λ = ħ √(C_λ / B_λ). Amplitude de point zéro : β² = (2λ+1) ħ / (2 B_λ ω_λ).
 *
 * a_s et a_c sont ceux du moteur (nucleus.py). Les fréquences réelles sont de
 * l'ordre de 10²¹ Hz : on montre le mode λ = 2 en 3,6 s, et les autres
 * modes gardent le vrai rapport de fréquences. Le modèle de la goutte est
 * grossier pour les noyaux légers : quand il prédit β > 0,3, il sort de son
 * domaine, et l'amplitude affichée est bornée (et signalée).
 *
 * Échanges de pions (Yukawa). Deux nucléons voisins échangent un pion
 * virtuel. Sa portée est ħ/(m_π c) = 1,41 fm et sa durée ħ/(m_π c²)
 * = 4,7·10⁻²⁴ s. Le voisin est choisi selon le potentiel de Yukawa
 * e^(−r/1,41 fm)/r. Entre un proton et un neutron, l'échange est chargé deux
 * fois sur trois (couplage √2 de π± contre 1 pour π⁰). Le proton qui émet un
 * π⁺ devient neutron, et le neutron qui l'absorbe devient proton : les
 * nucléons changent d'identité, la charge totale ne change pas.
 */
final class NuclearDrop {
    static final double A_SURFACE = 17.80;                    // MeV (= nucleus.py)
    static final double A_COULOMB = 0.711;                    // MeV (= nucleus.py)
    static final double R0_FM = 1.2;
    static final double NUCLEON_MC2 = 938.92;                 // MeV, moyenne proton/neutron
    static final double HBAR_C = 197.327;                     // MeV·fm
    static final double HBAR_MEV_S = 6.582119569e-22;         // MeV·s
    static final double PION_MC2 = 139.57;                    // MeV (π±)
    static final double PION_RANGE_FM = HBAR_C / PION_MC2;    // 1,41 fm
    static final double PION_TIME_S = HBAR_MEV_S / PION_MC2;  // 4,7·10⁻²⁴ s
    static final double SHOWN_BETA_MAX = 0.12;
    static final double OUT_OF_MODEL_BETA = 0.3;
    static final double QUADRUPOLE_SHOWN_S = 3.6;             // une vibration λ = 2 à l'écran
    static final double EXCHANGE_SHOWN_S = 1.7;               // un échange de pion à l'écran
    private static final double NEIGHBOUR_FM = 2.7;

    /** Un mode de vibration de surface de la goutte. */
    record Mode(int lambda, double stiffnessMev, double inertiaMevFm2, double hbarOmegaMev, double betaRms) {
        boolean stable() {
            return stiffnessMev > 0;
        }

        double periodSeconds() {
            return hbarOmegaMev > 0 ? 2 * Math.PI * HBAR_MEV_S / hbarOmegaMev : Double.POSITIVE_INFINITY;
        }
    }

    /** Le mode λ d'un noyau (Z, A) dans le modèle de la goutte liquide. */
    static Mode mode(int lambda, int z, int a) {
        double a13 = Math.cbrt(a);
        double radius = R0_FM * a13;
        double stiffness = (lambda - 1) * (lambda + 2) / (4 * Math.PI) * A_SURFACE * a13 * a13
                - 5.0 * (lambda - 1) / (2 * Math.PI * (2 * lambda + 1)) * A_COULOMB * z * (z - 1) / a13;
        double inertia = 3.0 / (4 * Math.PI * lambda) * a * NUCLEON_MC2 * radius * radius;   // B c², MeV·fm²
        double hbarOmega = stiffness > 0 ? HBAR_C * Math.sqrt(stiffness / inertia) : 0;
        double beta = stiffness > 0
                ? Math.sqrt((2 * lambda + 1) * HBAR_C * HBAR_C / (2 * inertia * hbarOmega)) : Double.POSITIVE_INFINITY;
        return new Mode(lambda, stiffness, inertia, hbarOmega, beta);
    }

    /** Un pion virtuel en vol entre deux nucléons. */
    static final class Exchange {
        final int from;
        final int to;
        final int charge;
        double age;

        Exchange(int from, int to, int charge) {
            this.from = from;
            this.to = to;
            this.charge = charge;
        }

        double progress() {
            return Math.min(1, age / EXCHANGE_SHOWN_S);
        }

        String name() {
            return charge > 0 ? "π⁺" : charge < 0 ? "π⁻" : "π⁰";
        }

        String quarks() {
            return charge > 0 ? "u d̄" : charge < 0 ? "d ū" : "(uū − dd̄)/√2";
        }
    }

    private double[][] base = new double[0][];
    private boolean[] proton = new boolean[0];
    private boolean[] busy = new boolean[0];
    private double[] tint = new double[0];
    private int[][] neighbours = new int[0][];
    private double[][] weights = new double[0][];
    private final List<Exchange> exchanges = new ArrayList<>();
    private Mode quadrupole;
    private Mode octupole;
    private double radiusFm = 1;
    private int protons;
    private long signature = Long.MIN_VALUE;
    private double phase2;
    private double phase3;
    private final double[] amplitude2 = new double[5];
    private final double[] amplitude3 = new double[7];
    private final double[] offset2 = new double[5];
    private final double[] offset3 = new double[7];
    private final double[] alpha2 = new double[5];
    private final double[] alpha3 = new double[7];
    private int swaps;
    private int charged;
    private int neutral;
    private double spawnDebt;
    private final Random random = new Random(29);

    /** Le moteur a envoyé les nucléons : on ne repart de zéro que si le noyau a changé. */
    void sync(List<Nucleon> nucleons, double nuclearRadiusFm) {
        long sig = 1469598103934665603L ^ nucleons.size();
        for (Nucleon nucleon : nucleons) {
            sig = sig * 1099511628211L ^ Float.floatToIntBits(nucleon.getX());
            sig = sig * 1099511628211L ^ Float.floatToIntBits(nucleon.getY());
            sig = sig * 1099511628211L ^ Float.floatToIntBits(nucleon.getZ());
            sig = sig * 1099511628211L ^ nucleon.getKind().getNumber();
        }
        if (sig == signature) {
            return;
        }
        signature = sig;
        int count = nucleons.size();
        base = new double[count][];
        proton = new boolean[count];
        busy = new boolean[count];
        tint = new double[count];
        protons = 0;
        for (int i = 0; i < count; i++) {
            Nucleon nucleon = nucleons.get(i);
            base[i] = new double[]{nucleon.getX(), nucleon.getY(), nucleon.getZ()};
            proton[i] = nucleon.getKind() == Nucleon.Kind.PROTON;
            tint[i] = proton[i] ? 1 : 0;
            protons += proton[i] ? 1 : 0;
        }
        radiusFm = Math.max(0.84, nuclearRadiusFm);
        neighbours = new int[count][];
        weights = new double[count][];
        for (int i = 0; i < count; i++) {
            List<Integer> near = new ArrayList<>();
            List<Double> pull = new ArrayList<>();
            for (int j = 0; j < count; j++) {
                double d = distance(base[i], base[j]);
                if (j != i && d < NEIGHBOUR_FM) {
                    near.add(j);
                    pull.add(Math.exp(-d / PION_RANGE_FM) / Math.max(0.5, d));   // Yukawa
                }
            }
            neighbours[i] = near.stream().mapToInt(Integer::intValue).toArray();
            weights[i] = pull.stream().mapToDouble(Double::doubleValue).toArray();
        }
        exchanges.clear();
        swaps = charged = neutral = 0;
        quadrupole = count >= 2 ? mode(2, protons, count) : null;
        octupole = count >= 2 ? mode(3, protons, count) : null;
        double shown2 = quadrupole == null ? 0 : Math.min(SHOWN_BETA_MAX, quadrupole.betaRms());
        double shown3 = octupole == null ? 0 : Math.min(SHOWN_BETA_MAX * 0.6, octupole.betaRms());
        // Σ_μ ⟨α_μ²⟩ = β² : chaque composante oscille avec l'amplitude β·√(2/(2λ+1)).
        for (int k = 0; k < 5; k++) {
            amplitude2[k] = shown2 * Math.sqrt(2.0 / 5);
            offset2[k] = random.nextDouble() * 2 * Math.PI;
        }
        for (int k = 0; k < 7; k++) {
            amplitude3[k] = shown3 * Math.sqrt(2.0 / 7);
            offset3[k] = random.nextDouble() * 2 * Math.PI;
        }
    }

    void tick(double dt) {
        if (quadrupole != null) {
            phase2 += dt * 2 * Math.PI / QUADRUPOLE_SHOWN_S;
            double ratio = octupole != null && octupole.hbarOmegaMev() > 0 && quadrupole.hbarOmegaMev() > 0
                    ? octupole.hbarOmegaMev() / quadrupole.hbarOmegaMev() : 1.6;
            phase3 += dt * 2 * Math.PI / QUADRUPOLE_SHOWN_S * ratio;
        }
        for (int k = 0; k < 5; k++) {
            alpha2[k] = amplitude2[k] * Math.cos(phase2 + offset2[k]);
        }
        for (int k = 0; k < 7; k++) {
            alpha3[k] = amplitude3[k] * Math.cos(phase3 + offset3[k]);
        }
        // Les pions arrivés : l'absorbeur change d'identité (s'il était chargé).
        for (int e = exchanges.size() - 1; e >= 0; e--) {
            Exchange exchange = exchanges.get(e);
            exchange.age += dt;
            if (exchange.age >= EXCHANGE_SHOWN_S) {
                if (exchange.charge != 0) {
                    proton[exchange.to] = !proton[exchange.to];
                    swaps++;
                }
                busy[exchange.from] = busy[exchange.to] = false;
                exchanges.remove(e);
            }
        }
        int count = base.length;
        if (count >= 2) {
            int target = Math.max(1, Math.min(6, Math.round(count / 4f)));
            spawnDebt += dt * target / EXCHANGE_SHOWN_S;
            int tries = 0;
            while (spawnDebt >= 1 && tries++ < 8) {
                if (exchanges.size() >= target || spawn()) {
                    spawnDebt -= 1;
                }
            }
            spawnDebt = Math.min(spawnDebt, 2);
        }
        double follow = 1 - Math.exp(-6 * dt);
        for (int i = 0; i < count; i++) {
            tint[i] += ((proton[i] ? 1 : 0) - tint[i]) * follow;
        }
    }

    /** Un échange entre un nucléon libre et un voisin libre, choisi selon Yukawa. */
    private boolean spawn() {
        int count = base.length;
        int from = random.nextInt(count);
        if (busy[from] || neighbours[from].length == 0) {
            return false;
        }
        double total = 0;
        for (int k = 0; k < neighbours[from].length; k++) {
            total += busy[neighbours[from][k]] ? 0 : weights[from][k];
        }
        if (total <= 0) {
            return false;
        }
        double pick = random.nextDouble() * total;
        int to = -1;
        for (int k = 0; k < neighbours[from].length; k++) {
            int j = neighbours[from][k];
            if (busy[j]) {
                continue;
            }
            pick -= weights[from][k];
            if (pick <= 0) {
                to = j;
                break;
            }
        }
        if (to < 0) {
            return false;
        }
        int charge = 0;
        if (proton[from] != proton[to] && random.nextDouble() < 2.0 / 3) {
            // p → n + π⁺  ou  n → p + π⁻ : l'émetteur change d'identité dès l'émission.
            charge = proton[from] ? 1 : -1;
            proton[from] = !proton[from];
            charged++;
        } else {
            neutral++;
        }
        busy[from] = busy[to] = true;
        exchanges.add(new Exchange(from, to, charge));
        return true;
    }

    /** Position déformée du nucléon i (fm) : le champ de déplacement irrotationnel ∝ r^(λ−1) Y_λμ. */
    void position(int i, double[] out) {
        double[] p = base[i];
        double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        if (r < 1e-6 || quadrupole == null) {
            out[0] = p[0];
            out[1] = p[1];
            out[2] = p[2];
            return;
        }
        double ux = p[0] / r;
        double uy = p[1] / r;
        double uz = p[2] / r;
        double relative = r / radiusFm;
        double delta = radiusFm * (relative * deformation(2, ux, uy, uz)
                + relative * relative * deformation(3, ux, uy, uz));
        out[0] = p[0] + ux * delta;
        out[1] = p[1] + uy * delta;
        out[2] = p[2] + uz * delta;
    }

    /** Rayon de la surface dans la direction u (fm). */
    double surface(double ux, double uy, double uz) {
        return radiusFm * (1 + deformation(2, ux, uy, uz) + deformation(3, ux, uy, uz));
    }

    private double deformation(int lambda, double ux, double uy, double uz) {
        double[] alpha = lambda == 2 ? alpha2 : alpha3;
        double sum = 0;
        for (int k = 0; k < alpha.length; k++) {
            sum += alpha[k] * OrbitalCloud.solid(lambda, k, ux, uy, uz);
        }
        return sum;
    }

    /** β instantané : √(Σ α²) du mode λ = 2. */
    double betaNow() {
        double sum = 0;
        for (double a : alpha2) {
            sum += a * a;
        }
        return Math.sqrt(sum);
    }

    int count() {
        return base.length;
    }

    boolean isProton(int i) {
        return proton[i];
    }

    /** 1 = couleur du proton, 0 = du neutron ; glisse quand l'identité change. */
    double tint(int i) {
        return tint[i];
    }

    List<Exchange> exchanges() {
        return exchanges;
    }

    Mode quadrupole() {
        return quadrupole;
    }

    Mode octupole() {
        return octupole;
    }

    /** Le modèle prédit-il une amplitude hors de son domaine (noyau léger, ou instable) ? */
    boolean outOfModel() {
        return quadrupole != null && (!quadrupole.stable() || quadrupole.betaRms() > OUT_OF_MODEL_BETA);
    }

    int swaps() {
        return swaps;
    }

    int chargedExchanges() {
        return charged;
    }

    int neutralExchanges() {
        return neutral;
    }

    /** Nombre de protons à cet instant (les pions chargés en vol emportent la différence). */
    int protonsNow() {
        int count = 0;
        for (boolean p : proton) {
            count += p ? 1 : 0;
        }
        return count;
    }

    int pionCharge() {
        int total = 0;
        for (Exchange exchange : exchanges) {
            total += exchange.charge;
        }
        return total;
    }

    double radiusFm() {
        return radiusFm;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
