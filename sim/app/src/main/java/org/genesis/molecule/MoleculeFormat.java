package org.genesis.molecule;

import org.genesis.app.I18n;

import java.util.Locale;

/** Les nombres du laboratoire moléculaire : virgule française, signe moins typographique. */
final class MoleculeFormat {
    private MoleculeFormat() {
    }

    static String decimal(double value, int digits) {
        String text = String.format(Locale.ROOT, "%." + digits + "f", value).replace('-', '−');
        if (text.matches("−0[.,]?0*")) text = text.substring(1);
        return I18n.language() == I18n.Language.FR ? text.replace('.', ',') : text;
    }

    static String signed(double value, int digits) {
        return (value > 0 ? "+" : "") + decimal(value, digits);
    }

    static String energy(double ev) {
        return decimal(ev, Math.abs(ev) >= 100 ? 2 : 3) + " eV";
    }

    /** Une durée simulée : fs, puis ps. */
    static String time(double fs) {
        return fs < 1000 ? decimal(fs, 1) + " fs" : decimal(fs / 1000, 3) + " ps";
    }

    /** Une demi-vie : en jours, en ans, en millions ou en milliards d'années. */
    static String halfLife(double years) {
        if (years < 1) return decimal(years * 365.25, years * 365.25 < 10 ? 1 : 0) + " " + I18n.text("jours");
        if (years < 1e4) return decimal(years, years < 100 ? 2 : 0) + " " + I18n.text("ans");
        if (years < 1e9) return decimal(years / 1e6, 2) + " " + I18n.text("millions d'années");
        return decimal(years / 1e9, 2) + " " + I18n.text("milliards d'années");
    }
}
