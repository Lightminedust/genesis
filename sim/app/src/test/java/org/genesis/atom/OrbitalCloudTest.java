package org.genesis.atom;

import java.util.Random;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Le nuage 3D : harmoniques, nœuds, conservation de la probabilité en projection, tranche de plongée. */
public class OrbitalCloudTest {

    private static OrbitalCloud.Layer layer(int n, int l, double radiusPm, int electrons, int... occupation) {
        return new OrbitalCloud.Layer(new OrbitalCloud.Shell(n, l, 3.25, radiusPm, electrons,
                occupation.length == 0 ? null : occupation, false, Color.WHITE, n + "spdf".substring(l, l + 1)));
    }

    @Test void laguerreFollowsItsClosedForms() {
        for (double x : new double[]{0, 0.7, 3, 11}) {
            assertEquals(1 + 3 - x, OrbitalCloud.laguerre(1, 3, x), 1e-12);
            assertEquals(x * x / 2 - 3 * x + 3, OrbitalCloud.laguerre(2, 1, x), 1e-12);
        }
    }

    @Test void eachSubshellHasNMinusLMinusOneRadialNodes() {
        for (int n = 1; n <= 7; n++) {
            for (int l = 0; l < n && l <= 3; l++) {
                int changes = 0;
                double previous = OrbitalCloud.laguerre(n - l - 1, 2 * l + 1, 1e-6);
                for (double rho = 0.01; rho < 4 * n + 24; rho += 0.01) {
                    double value = OrbitalCloud.laguerre(n - l - 1, 2 * l + 1, rho);
                    if (Math.signum(value) != Math.signum(previous)) {
                        changes++;
                    }
                    previous = value;
                }
                assertEquals(n - l - 1, changes, n + "," + l);
            }
        }
    }

    @Test void theMostProbableRadiusIsBohrsForCircularStates() {
        // r²R² est maximal en ρ = 2n pour l = n − 1, soit r = n² a₀ / Z.
        assertEquals(2, OrbitalCloud.Profile.of(1, 0).rhoPeak, 0.05);
        assertEquals(4, OrbitalCloud.Profile.of(2, 1).rhoPeak, 0.05);
        assertEquals(6, OrbitalCloud.Profile.of(3, 2).rhoPeak, 0.05);
        assertEquals(1, OrbitalCloud.Profile.of(4, 0).cdf(OrbitalCloud.Profile.of(4, 0).rhoMax), 1e-9);
    }

    @Test void realHarmonicsAreOrthonormalAndFullShellsAreSpherical() {
        int count = 40000;
        double[] u = new double[3];
        for (int l = 1; l <= 3; l++) {
            double[][] gram = new double[2 * l + 1][2 * l + 1];
            for (int i = 0; i < count; i++) {
                OrbitalCloud.fibonacci(i, count, u);
                double sum = 0;
                for (int a = 0; a <= 2 * l; a++) {
                    double ya = OrbitalCloud.solid(l, a, u[0], u[1], u[2]);
                    sum += ya * ya;
                    for (int b = 0; b <= 2 * l; b++) {
                        gram[a][b] += ya * OrbitalCloud.solid(l, b, u[0], u[1], u[2]) * 4 * Math.PI / count;
                    }
                }
                // Théorème d'Unsöld : Σ_m |Y_lm|² = (2l + 1) / 4π dans toutes les directions.
                assertEquals((2 * l + 1) / (4 * Math.PI), sum, 1e-6);
            }
            for (int a = 0; a <= 2 * l; a++) {
                for (int b = 0; b <= 2 * l; b++) {
                    assertEquals(a == b ? 1 : 0, gram[a][b], 2e-3, "l=" + l + " " + a + "," + b);
                }
            }
        }
    }

    /** ∫ colonne d²b sur le plan image = nombre d'électrons, quelle que soit l'orientation. */
    private static double integrate(OrbitalCloud.Layer layer, double[] toward) {
        double[] first = new double[3];
        double[] second = new double[3];
        double[] helper = Math.abs(toward[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
        cross(toward, helper, first);
        cross(toward, first, second);
        double reach = layer.profile.rhoMax * layer.a;
        int radii = 600;
        int angles = 72;
        double total = 0;
        for (int i = 0; i < radii; i++) {
            double b = (i + 0.5) / radii * reach;
            for (int k = 0; k < angles; k++) {
                double angle = (k + 0.5) * 2 * Math.PI / angles;
                double x = b * Math.cos(angle);
                double y = b * Math.sin(angle);
                total += layer.column(x * first[0] + y * second[0], x * first[1] + y * second[1],
                        x * first[2] + y * second[2], toward, false) * b * (reach / radii) * (2 * Math.PI / angles);
            }
        }
        return total;
    }

    @Test void projectionKeepsEveryElectron() {
        double[] view = {0.3, -0.5, 0.81};
        double norm = Math.sqrt(view[0] * view[0] + view[1] * view[1] + view[2] * view[2]);
        for (int k = 0; k < 3; k++) {
            view[k] /= norm;
        }
        assertEquals(2, integrate(layer(2, 0, 65, 2, 2), view), 0.02);
        assertEquals(1, integrate(layer(2, 1, 81, 1, 1, 0, 0), view), 0.01);
        assertEquals(1, integrate(layer(3, 2, 159, 1, 1, 0, 0, 0, 0), view), 0.01);
        assertEquals(3, integrate(layer(5, 3, 64, 3, 1, 1, 1, 0, 0, 0, 0), view), 0.03);
    }

    @Test void theFastPathMatchesTheDirectIntegral() {
        Camera3D camera = new Camera3D();
        camera.yaw = 0.7;
        camera.pitch = -0.4;
        double[] right = new double[3];
        double[] down = new double[3];
        double[] toward = new double[3];
        OrbitalCloud.axes(camera, right, down, toward);
        Random random = new Random(4);
        for (OrbitalCloud.Layer layer : new OrbitalCloud.Layer[]{layer(2, 1, 65, 2, 1, 1, 0),
                layer(3, 2, 76, 6, 2, 1, 1, 1, 1), layer(5, 3, 64, 3, 1, 1, 1, 0, 0, 0, 0), layer(4, 0, 190, 2, 2)}) {
            layer.prepare(right, down, toward);
            layer.useSlab(Double.POSITIVE_INFINITY);
            for (int i = 0; i < 40; i++) {
                double x = (random.nextDouble() - 0.5) * 3 * layer.peakPm();
                double y = (random.nextDouble() - 0.5) * 3 * layer.peakPm();
                double direct = layer.column(x * right[0] + y * down[0], x * right[1] + y * down[1],
                        x * right[2] + y * down[2], toward, true);
                assertEquals(direct, layer.columnFast(x, y), 1e-6 * layer.columnMax + 1e-3 * direct);
            }
        }
    }

    @Test void aThinSlabSeesOnlyTheLocalDensity() {
        OrbitalCloud.Layer core = layer(1, 0, 9.3, 2, 2);
        core.useSlab(Double.POSITIVE_INFINITY);
        double full = core.columnFast(0, 0);
        double previous = full;
        for (double slab : new double[]{20, 5, 1, 0.1}) {
            core.useSlab(slab);
            double column = core.columnFast(0, 0);
            assertTrue(column <= previous + 1e-12, "plus mince, moins d'électrons vus");
            previous = column;
        }
        // Tranche mince au centre : 2T · n(0), avec n(0) = N/(4π) · R̃²(0) / a³ et R̃²(0) = 1/2 pour 1s.
        double expected = 2 * 0.1 * 2 / (4 * Math.PI) * 0.5 / Math.pow(core.a, 3);
        assertEquals(expected, previous, 0.03 * expected);
    }

    @Test void theGulfDarkensTheCentre() {
        OrbitalCloud.Radial linear = OrbitalCloud.linear(4);
        assertEquals(1 / 16.0, OrbitalCloud.areaRatio(linear, 10), 1e-4);
        assertEquals(1 / 16.0, OrbitalCloud.areaRatio(linear, 300), 1e-4);
        double k = 60;
        OrbitalCloud.Radial log = new OrbitalCloud.Radial() {
            @Override
            public double px(double pm) {
                return k * Math.log10(1 + pm * 1000);
            }

            @Override
            public double length(double px) {
                return (Math.pow(10, px / k) - 1) / 1000;
            }
        };
        // Près du noyau, l'image est très dilatée : peu de surface vraie par pixel, donc presque rien à voir.
        assertTrue(OrbitalCloud.areaRatio(log, 20) < 1e-6 * OrbitalCloud.areaRatio(log, 300));
    }

    private static void cross(double[] a, double[] b, double[] out) {
        double x = a[1] * b[2] - a[2] * b[1];
        double y = a[2] * b[0] - a[0] * b[2];
        double z = a[0] * b[1] - a[1] * b[0];
        double norm = Math.sqrt(x * x + y * y + z * z);
        out[0] = x / norm;
        out[1] = y / norm;
        out[2] = z / norm;
    }
}
