package org.genesis.common;

import javafx.scene.paint.Color;

import java.util.Locale;

/**
 * Outils de rendu partagés, sans état : couleurs physiques, formatage des
 * grandeurs, barres d'échelle. Isolés ici pour être testés sans JavaFX.
 */
public final class Visuals {

    public static final double PICOMETRE = 1e-12;
    public static final double FEMTOMETRE = 1e-15;
    public static final double ATTOMETRE = 1e-18;

    /** Couleur d'un électron selon sa sous-couche : la règle de Klechkowski devient lisible. */
    public static final Color[] SUBSHELL_COLORS = {
            Color.web("#7DD3FC"),   // s
            Color.web("#86EFAC"),   // p
            Color.web("#FDBA74"),   // d
            Color.web("#F0ABFC"),   // f
    };

    public static final Color PROTON = Color.web("#F87171");
    public static final Color NEUTRON = Color.web("#94A3B8");
    public static final Color UV = Color.web("#C4B5FD");
    public static final Color IR = Color.web("#B91C1C");

    private Visuals() {
    }

    public static Color subshellColor(int l) {
        return SUBSHELL_COLORS[Math.max(0, Math.min(SUBSHELL_COLORS.length - 1, l))];
    }

    /**
     * Couleur d'une lumière monochromatique, calculée. Fonctions colorimétriques
     * CIE 1931 (ajustement analytique de Wyman, Sloan et Shirley, 2013), puis
     * XYZ → sRGB linéaire. Un spectre pur est plus saturé que tout écran : les
     * composantes négatives (hors gamut) sont ramenées à 0, puis on normalise à
     * la plus forte et on applique le gamma sRGB. Aux deux bouts du visible,
     * l'œil est moins sensible : la luminosité baisse (380–420 nm et
     * 700–780 nm). Hors du visible, une couleur conventionnelle : l'œil ne voit
     * rien, et un libellé UV / IR le dit.
     */
    public static Color wavelengthColor(double nanometres) {
        if (nanometres < 380) {
            return UV;
        }
        if (nanometres > 780) {
            return IR;
        }
        double[] xyz = cie1931(nanometres);
        double red = Math.max(0, 3.2406 * xyz[0] - 1.5372 * xyz[1] - 0.4986 * xyz[2]);
        double green = Math.max(0, -0.9689 * xyz[0] + 1.8758 * xyz[1] + 0.0415 * xyz[2]);
        double blue = Math.max(0, 0.0557 * xyz[0] - 0.2040 * xyz[1] + 1.0570 * xyz[2]);
        double top = Math.max(red, Math.max(green, blue));
        double factor;
        if (nanometres < 420) {
            factor = 0.3 + 0.7 * (nanometres - 380) / 40.0;
        } else if (nanometres > 700) {
            factor = 0.3 + 0.7 * (780 - nanometres) / 80.0;
        } else {
            factor = 1.0;
        }
        return Color.color(srgb(red / top * factor), srgb(green / top * factor), srgb(blue / top * factor));
    }

    /** x̄, ȳ, z̄ de la CIE 1931 (observateur 2°), ajustement multi-lobes de Wyman, Sloan et Shirley. */
    static double[] cie1931(double nm) {
        double x = 1.056 * lobe(nm, 599.8, 37.9, 31.0) + 0.362 * lobe(nm, 442.0, 16.0, 26.7)
                - 0.065 * lobe(nm, 501.1, 20.4, 26.2);
        double y = 0.821 * lobe(nm, 568.8, 46.9, 40.5) + 0.286 * lobe(nm, 530.9, 16.3, 31.1);
        double z = 1.217 * lobe(nm, 437.0, 11.8, 36.0) + 0.681 * lobe(nm, 459.0, 26.0, 13.8);
        return new double[]{x, y, z};
    }

    private static double lobe(double nm, double centre, double below, double above) {
        double t = (nm - centre) / (nm < centre ? below : above);
        return Math.exp(-0.5 * t * t);
    }

    /** Codage gamma sRGB d'une composante linéaire. */
    private static double srgb(double linear) {
        double c = Math.max(0, Math.min(1, linear));
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    public static String region(double nanometres) {
        if (nanometres < 380) {
            return "UV";
        }
        if (nanometres > 780) {
            return "IR";
        }
        return "visible";
    }

    /**
     * Une longueur physique dans l'unité la plus parlante : nm, pm, fm ou am.
     * On bascule d'unité à 0,1 plutôt qu'à 1, pour écrire « 0,84 fm » (le
     * rayon du proton, tel qu'on le cite) plutôt que « 840 am ».
     */
    public static String formatLength(double metres) {
        double absolute = Math.abs(metres);
        if (absolute >= 1e-9) {
            return format(metres / 1e-9) + " nm";
        }
        if (absolute >= 0.1 * PICOMETRE) {
            return format(metres / PICOMETRE) + " pm";
        }
        if (absolute >= 0.1 * FEMTOMETRE) {
            return format(metres / FEMTOMETRE) + " fm";
        }
        return format(metres / ATTOMETRE) + " am";
    }

    /** Trois chiffres significatifs au plus, virgule décimale à la française. */
    public static String format(double value) {
        double absolute = Math.abs(value);
        String text;
        if (absolute >= 100) {
            text = String.format(Locale.ROOT, "%.0f", value);
        } else if (absolute >= 10) {
            text = String.format(Locale.ROOT, "%.1f", value);
        } else {
            text = String.format(Locale.ROOT, "%.2f", value);
        }
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text.replace('.', ',');
    }

    public static String signed(double value, int decimals) {
        return String.format(Locale.ROOT, "%+." + decimals + "f", value).replace('.', ',');
    }

    public static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value).replace('.', ',');
    }

    /**
     * Longueur « ronde » (1, 2 ou 5 × 10^k mètres) pour une barre d'échelle
     * d'environ `targetPixels` pixels.
     */
    public static double niceScaleLength(double metresPerPixel, double targetPixels) {
        double raw = metresPerPixel * targetPixels;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double mantissa = raw / magnitude;
        double rounded = mantissa >= 5 ? 5 : mantissa >= 2 ? 2 : 1;
        return rounded * magnitude;
    }

    /** Lissage « smoothstep », pour des mouvements qui démarrent et s'arrêtent en douceur. */
    public static double ease(double t) {
        double clamped = Math.max(0, Math.min(1, t));
        return clamped * clamped * (3 - 2 * clamped);
    }

    public static String superscript(int value) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder builder = new StringBuilder();
        for (char character : Integer.toString(value).toCharArray()) {
            builder.append(character == '-' ? '⁻' : digits.charAt(character - '0'));
        }
        return builder.toString();
    }

    private static final double YEAR_S = 365.25 * 86400.0;

    /** Une durée lisible, de 10⁻²² s au milliard d'années (comme units.py côté moteur). */
    public static String duration(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return "infinie";
        }
        if (seconds <= 0) {
            return "0 s";
        }
        if (seconds >= 1e-15 && seconds < 1e-12) {
            return significant(seconds * 1e15) + " fs";
        }
        if (seconds >= 1e-12 && seconds < 1e-9) {
            return significant(seconds * 1e12) + " ps";
        }
        if (seconds < 1e-9) {
            int exponent = (int) Math.floor(Math.log10(seconds));
            return fixed(seconds / Math.pow(10, exponent), 1) + " × 10" + superscript(exponent) + " s";
        }
        double[][] units = {{1e-6, 1e-9}, {1e-3, 1e-6}, {1, 1e-3}, {120, 1}, {7200, 60}, {2 * 86400, 3600},
                {2 * YEAR_S, 86400}, {2e6 * YEAR_S, YEAR_S}, {2e9 * YEAR_S, 1e6 * YEAR_S}};
        String[] names = {"ns", "µs", "ms", "s", "min", "h", "jours", "ans", "millions d'années"};
        for (int i = 0; i < units.length; i++) {
            if (seconds < units[i][0]) {
                return significant(seconds / units[i][1]) + " " + names[i];
            }
        }
        return significant(seconds / (1e9 * YEAR_S)) + " milliards d'années";
    }

    /** Trois chiffres significatifs, sans exposant ; les milliers séparés par une espace. */
    public static String significant(double value) {
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%,d", Math.round(value)).replace(',', ' ');
        }
        String text = new java.math.BigDecimal(value).round(new java.math.MathContext(3)).stripTrailingZeros()
                .toPlainString();
        return text.replace('.', ',');
    }

    public static String hex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
