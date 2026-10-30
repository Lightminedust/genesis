package org.genesis.molecule;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Une grille calculée par le moteur : ρ (densité électronique), Δρ (densité de déformation) ou ψ (une orbitale),
 * en points réguliers autour de la molécule. Les entiers 16 bits reçus sont remis en valeurs physiques :
 * ρ = peak · (q / 65535)⁴, et pour les grandeurs signées valeur = peak · signe · (|q| / 32767)².
 *
 * <p>Dans la vue ρ, le moteur envoie aussi : le recouvrement entre atomes ρ_ov (bonds), la part de la densité qui
 * fait les liaisons — positive où les orbitales s'additionnent, négative où elles s'opposent ; le potentiel
 * électrostatique (esp, V).
 */
final class DensityGrid {
    static final int DENSITY = 0, DEFORMATION = 1, ORBITAL = 2;
    final int kind, orbital, version, nx, ny, nz;
    final double ox, oy, oz, spacing, peak;
    final float[] values;
    final double[] positions;

    private DensityGrid(int kind, int orbital, int version, int nx, int ny, int nz, double ox, double oy, double oz,
                         double spacing, double peak, float[] values, double[] positions) {
        this.kind = kind;
        this.orbital = orbital;
        this.version = version;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.spacing = spacing;
        this.peak = peak;
        this.values = values;
        this.positions = positions;
    }

    static DensityGrid of(com.studio.genesis.grpc.MoleculeGrid message) {
        if (message.getCountsCount() < 3 || message.getData().isEmpty()) return null;
        return decode(message, message.getData(), message.getPeak(), message.getKind() != DENSITY);
    }

    /** Le recouvrement des liaisons ρ_ov (vue ρ seulement ; null sinon). */
    static DensityGrid bonds(com.studio.genesis.grpc.MoleculeGrid message) {
        if (message.getCountsCount() < 3 || message.getBonds().isEmpty()) return null;
        return decode(message, message.getBonds(), message.getBondsPeak(), true);
    }

    /** Le potentiel électrostatique φ (V) de la molécule (vue ρ seulement ; null sinon). */
    static DensityGrid potential(com.studio.genesis.grpc.MoleculeGrid message) {
        if (message.getCountsCount() < 3 || message.getEsp().isEmpty()) return null;
        return decode(message, message.getEsp(), message.getEspPeak(), true);
    }

    private static DensityGrid decode(com.studio.genesis.grpc.MoleculeGrid message, ByteString data, double peak,
                                      boolean signed) {
        int nx = message.getCounts(0), ny = message.getCounts(1), nz = message.getCounts(2);
        ByteBuffer buffer = data.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[nx * ny * nz];
        for (int i = 0; i < values.length && buffer.remaining() >= 2; i++) {
            if (signed) {
                double q = buffer.getShort() / 32767.0;
                values[i] = (float) (Math.signum(q) * q * q * peak);
            } else {
                double q = (buffer.getShort() & 0xFFFF) / 65535.0;
                q *= q;
                values[i] = (float) (q * q * peak);
            }
        }
        double[] positions = new double[message.getPositionsCount()];
        for (int i = 0; i < positions.length; i++) positions[i] = message.getPositions(i);
        return new DensityGrid(message.getKind(), message.getOrbital(), message.getVersion(), nx, ny, nz,
                message.getOrigin(0), message.getOrigin(1), message.getOrigin(2), message.getSpacing(), peak, values,
                positions);
    }

    /** Une grille construite directement (tests, aperçus). */
    static DensityGrid of(int kind, int nx, int ny, int nz, double ox, double oy, double oz, double spacing,
                           float[] values) {
        double peak = 0;
        for (float v : values) peak = Math.max(peak, Math.abs(v));
        return new DensityGrid(kind, -1, 0, nx, ny, nz, ox, oy, oz, spacing, peak, values, new double[0]);
    }

    /** La même grille, d'autres valeurs (la grandeur prête pour le rendu). */
    DensityGrid mapped(float[] field) {
        return new DensityGrid(kind, orbital, version, nx, ny, nz, ox, oy, oz, spacing, peak, field, positions);
    }

    /**
     * La grille deux fois plus fine, par interpolation cubique (Catmull–Rom) séparable : une grille grossière
     * (pendant la dynamique) donnerait sinon des surfaces à facettes. Les points du moteur sont conservés.
     */
    DensityGrid refined() {
        int mx = 2 * nx - 1, my = 2 * ny - 1, mz = 2 * nz - 1;
        float[] a = new float[mx * ny * nz];
        for (int j = 0; j < ny; j++) for (int k = 0; k < nz; k++) {
            final int jj = j, kk = k;
            for (int i = 0; i < mx; i++) a[(i * ny + jj) * nz + kk] = cubic(i, nx, n -> values[(n * ny + jj) * nz + kk]);
        }
        float[] b = new float[mx * my * nz];
        for (int i = 0; i < mx; i++) for (int k = 0; k < nz; k++) {
            final int ii = i, kk = k;
            for (int j = 0; j < my; j++) b[(ii * my + j) * nz + kk] = cubic(j, ny, n -> a[(ii * ny + n) * nz + kk]);
        }
        float[] c = new float[mx * my * mz];
        java.util.stream.IntStream.range(0, mx).parallel().forEach(i -> {
            for (int j = 0; j < my; j++) {
                final int base = (i * my + j) * nz, out = (i * my + j) * mz;
                for (int k = 0; k < mz; k++) c[out + k] = cubic(k, nz, n -> b[base + n]);
            }
        });
        return new DensityGrid(kind, orbital, version, mx, my, mz, ox, oy, oz, spacing / 2, peak, c, positions);
    }

    /** Le point fin `index` (pair : un point d'origine ; impair : le milieu, par Catmull–Rom). */
    private static float cubic(int index, int count, java.util.function.IntToDoubleFunction at) {
        int i = index / 2;
        if (index % 2 == 0) return (float) at.applyAsDouble(i);
        double p0 = at.applyAsDouble(Math.max(0, i - 1)), p1 = at.applyAsDouble(i), p2 = at.applyAsDouble(i + 1),
                p3 = at.applyAsDouble(Math.min(count - 1, i + 2));
        return (float) ((-p0 + 9 * p1 + 9 * p2 - p3) / 16);
    }

    double maxX() { return ox + (nx - 1) * spacing; }
    double maxY() { return oy + (ny - 1) * spacing; }
    double maxZ() { return oz + (nz - 1) * spacing; }

    /** Position (Å) du point (i, j, k). */
    double x(int i) { return ox + i * spacing; }
    double y(int j) { return oy + j * spacing; }
    double z(int k) { return oz + k * spacing; }

    /** Interpolation trilinéaire (Å) ; 0 hors de la grille. */
    float sample(double x, double y, double z) {
        double fx = (x - ox) / spacing, fy = (y - oy) / spacing, fz = (z - oz) / spacing;
        if (fx < 0 || fy < 0 || fz < 0 || fx >= nx - 1 || fy >= ny - 1 || fz >= nz - 1) return 0f;
        int i = (int) fx, j = (int) fy, k = (int) fz;
        float tx = (float) (fx - i), ty = (float) (fy - j), tz = (float) (fz - k);
        int base = (i * ny + j) * nz + k;
        int sx = ny * nz, sy = nz;
        float c000 = values[base], c001 = values[base + 1];
        float c010 = values[base + sy], c011 = values[base + sy + 1];
        float c100 = values[base + sx], c101 = values[base + sx + 1];
        float c110 = values[base + sx + sy], c111 = values[base + sx + sy + 1];
        float c00 = c000 + (c001 - c000) * tz, c01 = c010 + (c011 - c010) * tz;
        float c10 = c100 + (c101 - c100) * tz, c11 = c110 + (c111 - c110) * tz;
        float c0 = c00 + (c01 - c00) * ty, c1 = c10 + (c11 - c10) * ty;
        return c0 + (c1 - c0) * tx;
    }

    /** Le gradient de |valeur| (différences centrées sur un demi-pas), pour les normales des surfaces. */
    double[] gradient(double x, double y, double z) {
        double h = spacing * .5;
        return new double[]{
                Math.abs(sample(x + h, y, z)) - Math.abs(sample(x - h, y, z)),
                Math.abs(sample(x, y + h, z)) - Math.abs(sample(x, y - h, z)),
                Math.abs(sample(x, y, z + h)) - Math.abs(sample(x, y, z - h))};
    }
}
