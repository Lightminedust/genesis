package org.genesis.chain;

import org.genesis.app.I18n;
import org.genesis.common.Visuals;

import java.util.Locale;

/** Les grandeurs du laboratoire, écrites pour être lues : puissances de dix, unités choisies, virgule française. */
final class ChainFormat {
    private ChainFormat() {
    }

    static String decimal(double value, int digits) {
        String text = String.format(Locale.ROOT, "%." + digits + "f", value);
        return I18n.language() == I18n.Language.FR ? text.replace('.', ',') : text;
    }

    /** Trois chiffres significatifs ; au-delà de 10⁴ ou en deçà de 10⁻³, « 1,2 × 10¹⁷ ». */
    static String number(double value) {
        if (value == 0) return "0";
        if (!Double.isFinite(value)) return "∞";
        double magnitude = Math.abs(value);
        if (magnitude >= 1e-3 && magnitude < 1e4) return (value < 0 ? "−" : "") + Visuals.significant(magnitude);
        int exponent = (int) Math.floor(Math.log10(magnitude));
        double mantissa = value / Math.pow(10, exponent);
        String digits = decimal(mantissa, 2).replaceAll("([,.]\\d*?)0+$", "$1").replaceAll("[,.]$", "");
        return (digits.equals("1") ? "" : digits.equals("-1") ? "−" : digits + " × ") + "10" + Visuals.superscript(exponent);
    }

    static String signed(double value) {
        return (value > 0 ? "+" : "") + number(value);
    }

    static String length(double cm) {
        if (cm >= 100) return number(cm / 100) + " m";
        if (cm < 1) return number(cm * 10) + " mm";
        return number(cm) + " cm";
    }

    static String mass(double kg) {
        if (kg >= 1000) return number(kg / 1000) + " t";
        if (kg < 1) return number(kg * 1000) + " g";
        return number(kg) + " kg";
    }

    static String energyJ(double joules) {
        String[][] units = {{"1e15", "PJ"}, {"1e12", "TJ"}, {"1e9", "GJ"}, {"1e6", "MJ"}, {"1e3", "kJ"}};
        for (String[] u : units) {
            double size = Double.parseDouble(u[0]);
            if (joules >= size) return number(joules / size) + " " + u[1];
        }
        return number(joules) + " J";
    }

    static String energyEv(double ev) {
        if (ev >= 1e6) return number(ev / 1e6) + " MeV";
        if (ev >= 1e3) return number(ev / 1e3) + " keV";
        return number(ev) + " eV";
    }

    static String time(double seconds) {
        double s = Math.abs(seconds);
        if (s == 0) return "0 s";
        if (!Double.isFinite(s)) return I18n.text("infinie");
        if (s < 1e-6) return number(seconds * 1e9) + " ns";
        if (s < 1e-3) return number(seconds * 1e6) + " µs";
        if (s < 1) return number(seconds * 1e3) + " ms";
        if (s < 120) return number(seconds) + " s";
        if (s < 7200) return number(seconds / 60) + " min";
        return number(seconds / 3600) + " h";
    }

    static String temperature(double kelvin) {
        return number(Math.round(kelvin)) + " K";
    }

    static String percent(double fraction) {
        return number(100 * fraction) + " %";
    }

    /** « U235 » ou « U-235 » → « ²³⁵U ». */
    static String nuclide(String symbol, int a) {
        return Visuals.superscript(a) + symbol;
    }
}
