package org.genesis.molecule;

import org.genesis.app.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Les nombres du laboratoire moléculaire : virgule française, signe moins typographique. */
final class MoleculeFormat {
    private MoleculeFormat() {
    }

    /**
     * Un texte de noms écrit par le moteur — « formé : 2 × H₂O · eau  —  défait : HO · radical hydroxyle », « H₂O · eau +
     * CO₂ · dioxyde de carbone » — dans la langue choisie : chaque nom traduit ; formules, comptes et signes tels quels.
     */
    static String names(String text) {
        if (I18n.language() == I18n.Language.FR || text.isEmpty()) return text;
        if (!text.contains(" · ")) return I18n.text(text);
        StringBuilder out = new StringBuilder();
        String[] clauses = text.split("  —  ", -1);
        for (int c = 0; c < clauses.length; c++) {
            if (c > 0) out.append("  —  ");
            String clause = clauses[c];
            int colon = clause.indexOf(" : ");
            if (colon > 0 && !clause.substring(0, colon).contains(" · ")) {
                out.append(I18n.text(clause.substring(0, colon))).append(": ");
                clause = clause.substring(colon + 3);
            }
            java.util.regex.Matcher between = java.util.regex.Pattern.compile(", | \\+ ").matcher(clause);
            int last = 0;
            while (true) {
                boolean found = between.find();
                String item = clause.substring(last, found ? between.start() : clause.length());
                int dot = item.indexOf(" · ");
                out.append(dot < 0 ? item : item.substring(0, dot + 3) + I18n.text(item.substring(dot + 3)));
                if (!found) break;
                out.append(between.group());
                last = between.end();
            }
        }
        return out.toString();
    }

    private static String[] elements;

    /** Le nom d'un élément dans la langue choisie (« carbone », « carbon ») ; le symbole s'il manque. */
    static String element(int z) {
        if (elements == null) {
            List<String> names = new ArrayList<>();
            try (var in = MoleculeFormat.class.getResourceAsStream("/i18n/elements.tsv")) {
                if (in != null) {
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                        if (!line.isBlank() && !line.startsWith("#")) names.add(line.split("\t")[0].trim());
                    }
                }
            } catch (java.io.IOException ignored) {
                // pas de noms : les symboles suffisent
            }
            elements = names.toArray(new String[0]);
        }
        if (z < 1 || z > elements.length) return MoleculeView.symbolOf(z);
        String name = I18n.text(elements[z - 1]);
        return I18n.language() == I18n.Language.FR ? name.toLowerCase(Locale.FRENCH) : name.toLowerCase(Locale.ROOT);
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

    /** Une durée estimée, à deux chiffres : « 240 fs », « 2,4 ps », « 170 ps », « 1,7 ns ». */
    static String rough(double fs) {
        double value = fs < 1000 ? fs : fs < 1e6 ? fs / 1e3 : fs / 1e6;
        String unit = fs < 1000 ? " fs" : fs < 1e6 ? " ps" : " ns";
        return decimal(value, value < 10 ? 1 : 0) + unit;
    }

    /** Une pression en atmosphères, les milliers séparés : « 3 000 atm » (« 3,000 atm » en anglais). */
    static String atm(double value) {
        Locale locale = I18n.language() == I18n.Language.FR ? Locale.FRENCH : Locale.ENGLISH;
        return String.format(locale, "%,d", Math.round(value)).replace('\u202F', '\u2009').replace('\u00A0', '\u2009') + " atm";
    }

    /** Une demi-vie : en jours, en ans, en millions ou en milliards d'années. */
    static String halfLife(double years) {
        if (years < 1) return decimal(years * 365.25, years * 365.25 < 10 ? 1 : 0) + " " + I18n.text("jours");
        if (years < 1e4) return decimal(years, years < 100 ? 2 : 0) + " " + I18n.text("ans");
        if (years < 1e9) return decimal(years / 1e6, 2) + " " + I18n.text("millions d'années");
        return decimal(years / 1e9, 2) + " " + I18n.text("milliards d'années");
    }
}
