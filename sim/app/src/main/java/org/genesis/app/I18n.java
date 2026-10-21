package org.genesis.app;

import javafx.beans.property.Property;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Text;
import javafx.stage.Window;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation-only localization. Simulation state and protocol values remain untouched.
 *
 * The program is written in French; English is produced at display time, in this order:
 * <ol>
 *   <li>a reviewed label ({@code en-overrides.tsv}) or an exact sentence ({@code en.tsv});</li>
 *   <li>a template ({@code en-templates.tsv}): numbers, nuclide labels (U-238, ²³⁸U) and element names are taken out
 *       as {0}, {1}… so that one entry covers every value the engine can send; numbers are put back with an English
 *       decimal point;</li>
 *   <li>a longer text is cut into sentences and parts (line breaks, " · ", runs of spaces, sentence ends) and each part
 *       is translated by the two steps above;</li>
 *   <li>known phrases ({@code en-fragments.tsv}) are replaced, but only if nothing French is left: a half-translated
 *       sentence reads worse than the French original, so the original is kept and {@link #missing} reports it.</li>
 * </ol>
 */
public final class I18n {
    public enum Language { FR, EN }

    private static final Preferences PREFS = Preferences.userNodeForPackage(I18n.class);
    // New preference key resets the initial choice to English for this release.
    private static final String LANGUAGE_PREF = "language.v2";
    private static final Set<String> ELEMENT_SYMBOLS = Set.of(("H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am Cm Bk Cf Es Fm Md No Lr Rf Db Sg Bh Hs Mt Ds Rg Cn Nh Fl Mc Lv Ts Og").split(" "));
    private static final Map<String, String> OVERRIDES = load("/i18n/en-overrides.tsv");
    private static final Map<String, String> ENGLISH = load("/i18n/en.tsv");
    private static final Map<String, String> TEMPLATES = load("/i18n/en-templates.tsv");
    private static final Map<String, String> ELEMENT_NAMES = elementNames();
    private static final Pattern SLOT = slotPattern();
    private static final Pattern SEGMENT = Pattern.compile(
            "\\n+|\\s+·\\s+|\\s+—\\s+|\\s{3,}|(?<=[.!?…;])\\s+(?=[\\p{Lu}\\d«(¹²³⁴⁵⁶⁷⁸⁹])");
    private static final Pattern FRENCH_WORD = Pattern.compile("(?iu)(?<!\\p{L})(de(?! Broglie)|en|au|neutre|ionisant|ionisants|non|le|la|les|des|du|un|une|et|est|sont|pour|avec|dans|sur|"
            + "par|aux|qui|que|ne|pas|ce|cette|ces|il|elle|ils|elles|se|sa|ses|leur|leurs|mais|ou|donc|sans|sous|vers|entre|"
            + "après|avant|noyau|noyaux|moteur|énergie|lumière|vitesse|temps|calcul|tir|choc|soudure|chaleur|étoile|niveau|"
            + "couche|fois|chaque|aucun|aucune|tout|toute|tous|toutes|trop|très|peu|encore|déjà|ici)(?!\\p{L})"
            + "|(?<!\\p{L})(?:l|d|qu|n|s|j|c)['’](?=\\p{L})|[éèêëàâçùûîïôœ]");
    private static final Pattern ENGLISH_WORD = Pattern.compile(
            "(?iu)(?<!\\p{L})(the|of|is|are|and|with|from|into|its|this|that|which|no longer|cannot)(?!\\p{L})");
    private static final Map<String, String> FRAGMENTS = fragments();
    private static final Pattern FRAGMENT_PATTERN = fragmentPattern();
    private static final Pattern SPACED_HEADING = Pattern.compile("\\p{L}\\u2009\\p{L}");
    private static final Pattern SPACED_CAPS = Pattern.compile("(?<!\\S)\\p{Lu} \\p{Lu} \\p{Lu}(?!\\S)");
    private static final Map<String, String> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(512, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 4096; }
    });
    private static final Map<Property<String>, String> SOURCES = new WeakHashMap<>();
    private static final Set<Object> VISITED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Property<String>> UPDATING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static Language language = "en".equalsIgnoreCase(System.getProperty("genesis.lang",
            PREFS.get(LANGUAGE_PREF, "en"))) ? Language.EN : Language.FR;

    private I18n() {}

    public static Language language() { return language; }

    public static Locale locale() { return language == Language.EN ? Locale.US : Locale.FRANCE; }

    public static void setLanguage(Language next) {
        if (language == next) return;
        language = next;
        // Une langue imposée au lancement (-Dgenesis.lang : tests, aperçus) ne remplace jamais le choix enregistré.
        if (System.getProperty("genesis.lang") == null) PREFS.put(LANGUAGE_PREF, next == Language.EN ? "en" : "fr");
        CACHE.clear();
        for (Map.Entry<Property<String>, String> item : new ArrayList<>(SOURCES.entrySet())) {
            display(item.getKey(), item.getValue());
        }
        for (Runnable listener : List.copyOf(LISTENERS)) listener.run();
    }

    public static void onChange(Runnable listener) { LISTENERS.add(listener); }

    public static String source(Labeled label) { return SOURCES.getOrDefault(label.textProperty(), label.getText()); }

    public static String text(String source) {
        if (source == null || language == Language.FR || source.isEmpty()) return source;
        if (ELEMENT_SYMBOLS.contains(source)) return source;
        return CACHE.computeIfAbsent(source, I18n::english);
    }

    private static String english(String source) {
        if (source.contains("   ") && SPACED_CAPS.matcher(source).find()) {
            String plain = source.replace("   ", "\u0000").replace(" ", "").replace("\u0000", " ");
            String translated = english(plain.toLowerCase(Locale.ROOT));
            return spacedCaps(translated);
        }
        // Section headings use thin spaces between letters. Translate their original
        // words first, then restore the typographic spacing in the selected language.
        if (source.indexOf('\u2009') >= 0 && SPACED_HEADING.matcher(source).find()) {
            String plain = source.replace("\u2009   ", " ").replace("\u2009", "");
            if (!plain.equals(source)) return spaced(english(plain));
        }
        String whole = whole(source);
        if (whole != null) return whole;
        // Already English (a piece translated before being joined): fragments would only damage it.
        if (!looksFrench(source) && ENGLISH_WORD.matcher(source).find()) return source;
        String parts = segments(source);
        if (parts != null) return parts;
        String fragmented = fragments(source);
        return looksFrench(fragmented) ? source : fragmented;
    }

    /** One piece of text, translated as a whole (reviewed label, exact sentence, or template), or null. */
    private static String whole(String source) {
        String override = OVERRIDES.get(source);
        if (override != null) return override;
        String exact = ENGLISH.get(source);
        if (exact != null && preservesNotation(source, exact)) return exact;
        String trimmed = source.strip();
        if (!trimmed.equals(source) && !trimmed.isEmpty()) {
            String inner = whole(trimmed);
            if (inner != null) {
                int start = source.indexOf(trimmed);
                return source.substring(0, start) + inner + source.substring(start + trimmed.length());
            }
            return null;
        }
        return template(source);
    }

    /** The text with numbers, nuclides and element names replaced by {0}, {1}…, and what was taken out. */
    private record Slots(String key, List<String> values, List<Character> kinds) {}

    private static Slots slots(String source) {
        Matcher matcher = SLOT.matcher(source);
        StringBuilder key = new StringBuilder();
        List<String> values = new ArrayList<>();
        List<Character> kinds = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            key.append(source, last, matcher.start()).append('{').append(values.size()).append('}');
            values.add(matcher.group());
            kinds.add(matcher.group("nuc") != null || matcher.group("sym") != null ? 'n' : matcher.group("elem") != null ? 'e' : '#');
            last = matcher.end();
        }
        key.append(source, last, source.length());
        return new Slots(key.toString(), values, kinds);
    }

    private static String template(String source) {
        Slots slots = slots(source);
        if (slots.values().isEmpty()) return null;
        String target = TEMPLATES.get(slots.key());
        if (target == null) return null;
        StringBuilder out = new StringBuilder();
        Matcher hole = Pattern.compile("\\{(\\d+)}").matcher(target);
        int last = 0;
        while (hole.find()) {
            int index = Integer.parseInt(hole.group(1));
            if (index >= slots.values().size()) return null;
            out.append(target, last, hole.start()).append(englishValue(slots.values().get(index), slots.kinds().get(index)));
            last = hole.end();
        }
        out.append(target.substring(last));
        return out.toString();
    }

    /** A value put back into an English template: a decimal comma becomes a point, an element name is translated. */
    private static String englishValue(String value, char kind) {
        if (kind == 'e') return ELEMENT_NAMES.getOrDefault(value, value);
        if (kind == '#') return value.replaceAll("(?<=\\d),(?=\\d)", ".");
        return value;
    }

    /** A longer text cut into parts, each translated as a whole; null unless every French part could be. */
    private static String segments(String source) {
        Matcher matcher = SEGMENT.matcher(source);
        if (!matcher.find()) return null;
        matcher.reset();
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (true) {
            boolean found = matcher.find();
            int end = found ? matcher.start() : source.length();
            String part = source.substring(last, end);
            if (!part.isBlank() && looksFrench(part)) {
                String translated = whole(part);
                if (translated == null) translated = segmentsOrNull(part);
                if (translated == null) return null;
                part = translated;
                changed = true;
            } else if (!part.isBlank()) {
                // Sans accent ni mot-outil (« Ag-107 reste Ag-107. »), une partie peut encore être française :
                // la table la reconnaît, sinon elle reste telle quelle.
                String translated = whole(part);
                if (translated != null && !translated.equals(part)) {
                    part = translated;
                    changed = true;
                }
            }
            out.append(part);
            if (!found) break;
            out.append(matcher.group());
            last = matcher.end();
        }
        return changed ? out.toString() : null;
    }

    private static String segmentsOrNull(String part) {
        // a part may still hold a label and its value ("Énergie : 4,2 MeV"): try the label alone
        int colon = part.indexOf(" : ");
        if (colon > 0) {
            String head = whole(part.substring(0, colon));
            String tail = part.substring(colon + 3);
            String tailEnglish = looksFrench(tail) ? whole(tail) : tail;
            if (head != null && tailEnglish != null) return head + ": " + tailEnglish;
        }
        return null;
    }

    private static String fragments(String source) {
        Matcher matcher = FRAGMENT_PATTERN.matcher(source);
        StringBuffer translated = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(translated, Matcher.quoteReplacement(FRAGMENTS.get(matcher.group())));
        }
        matcher.appendTail(translated);
        return translated.toString();
    }

    /** Whether a text still reads as French (an accented letter, or a French function word). */
    public static boolean looksFrench(String text) {
        return text != null && FRENCH_WORD.matcher(text).find();
    }

    /**
     * For checking coverage: the parts of a French text that English mode cannot translate yet, each given as the key a
     * translation table would need (the template key when the part holds numbers, nuclides or element names).
     */
    public static List<String> missing(String source) {
        List<String> out = new ArrayList<>();
        if (source == null || source.isBlank() || !looksFrench(source)) return out;
        if (source.contains("   ") && SPACED_CAPS.matcher(source).find()) {
            return missing(source.replace("   ", "\u0000").replace(" ", "").replace("\u0000", " ").toLowerCase(Locale.ROOT));
        }
        if (source.indexOf('\u2009') >= 0 && SPACED_HEADING.matcher(source).find()) {
            String plain = source.replace("\u2009   ", " ").replace("\u2009", "");
            if (!plain.equals(source)) return missing(plain);
        }
        if (whole(source) != null) return out;
        Matcher matcher = SEGMENT.matcher(source);
        int last = 0;
        boolean split = false;
        while (true) {
            boolean found = matcher.find();
            int end = found ? matcher.start() : source.length();
            String part = source.substring(last, end).strip();
            if (!part.isEmpty() && looksFrench(part) && whole(part) == null && segmentsOrNull(part) == null) {
                Slots slots = slots(part);
                out.add(slots.values().isEmpty() ? part : slots.key());
            }
            if (!found) break;
            split = true;
            last = matcher.end();
        }
        return out;
    }

    /**
     * For building the tables: the French parts of a text as the translator looks them up (one key per part, the
     * template key when the part holds numbers, nuclides or element names), whether or not a translation exists.
     */
    public static List<String> units(String source) {
        List<String> out = new ArrayList<>();
        if (source == null || source.isBlank() || !looksFrench(source)) return out;
        if (source.contains("   ") && SPACED_CAPS.matcher(source).find()) {
            return units(source.replace("   ", "\u0000").replace(" ", "").replace("\u0000", " ").toLowerCase(Locale.ROOT));
        }
        if (source.indexOf(' ') >= 0 && SPACED_HEADING.matcher(source).find()) {
            String plain = source.replace("    ", " ").replace(" ", "");
            if (!plain.equals(source)) return units(plain);
        }
        Matcher matcher = SEGMENT.matcher(source);
        int last = 0;
        while (true) {
            boolean found = matcher.find();
            int end = found ? matcher.start() : source.length();
            String part = source.substring(last, end).strip();
            if (!part.isEmpty() && looksFrench(part)) {
                Slots slots = slots(part);
                out.add(slots.values().isEmpty() ? part : slots.key());
            }
            if (!found) break;
            last = matcher.end();
        }
        return out;
    }

    /** The element names as the engine writes them, capitalised and in lower case, with their English names. */
    private static Map<String, String> elementNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : load("/i18n/elements.tsv").entrySet()) {
            names.put(entry.getKey(), entry.getValue());
            names.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static Pattern slotPattern() {
        List<String> names = new ArrayList<>(ELEMENT_NAMES.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        List<String> quoted = new ArrayList<>();
        for (String name : names) if (name.length() > 1) quoted.add(Pattern.quote(name));
        String elements = quoted.isEmpty() ? "(?!)" : String.join("|", quoted);
        // Ions (Fe⁻, Ca²⁺) ; W±, K±, B± et Ds± sont aussi des particules (boson W, kaons, mésons B et Ds) : pas des ions.
        List<String> ions = new ArrayList<>();
        for (String symbol : ELEMENT_SYMBOLS) if (!List.of("W", "K", "B", "Ds").contains(symbol)) ions.add(symbol);
        return Pattern.compile(
                "(?<nuc>(?<![\\p{L}\\d])[A-Z][a-z]?-\\d{1,3}m?(?![\\p{L}\\d])|(?<![\\p{L}\\d])[⁰¹²³⁴⁵⁶⁷⁸⁹]+[A-Z][a-z]?(?![a-z])"
                + "|(?<![\\p{L}\\d])(?:" + String.join("|", ions) + ")[⁰¹²³⁴⁵⁶⁷⁸⁹]*[⁻⁺](?![\\p{L}]))"
                + "|(?<sym>\\((?:" + String.join("|", ELEMENT_SYMBOLS) + ")\\))"
                + "|(?<elem>(?<![\\p{L}'’-])(?:" + elements + ")(?![\\p{L}'’]))"
                + "|(?<num>(?<![\\p{L}\\d_.,])[-−+]?\\d+(?:[\\u202F\\u00A0\\u2009]\\d{3})*(?:[.,]\\d+)?"
                + "(?:(?:\\s?[·×]\\s?10)?[⁻⁺]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+|[eE][-+]?\\d+)?)");
    }

    private static String spaced(String source) {
        StringBuilder result = new StringBuilder(source.length() * 2);
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            result.append(ch);
            if (i + 1 < source.length()) result.append(ch == ' ' ? "  " : "\u2009");
        }
        return result.toString();
    }

    private static String spacedCaps(String source) {
        StringBuilder result = new StringBuilder(source.length() * 2);
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            boolean greek = Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.GREEK;
            result.append(greek ? ch : Character.toUpperCase(ch));
            boolean combining = i + 1 < source.length()
                    && Character.getType(source.charAt(i + 1)) == Character.NON_SPACING_MARK;
            if (i + 1 < source.length() && !combining) result.append(ch == ' ' ? "  " : " ");
        }
        return result.toString();
    }

    /** Machine-assisted catalog entries must never alter equations or format placeholders. */
    private static boolean preservesNotation(String source, String target) {
        String sourceDigits = source.chars().filter(Character::isDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String targetDigits = target.chars().filter(Character::isDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (!sourceDigits.equals(targetDigits)) return false;
        String protectedGlyphs = "+=/^ħℏ∑√∫∂∝≈≤≥→↔−×÷±θβλνγρμκσπΩ₀₁₂₃₄₅₆₇₈₉⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻";
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (protectedGlyphs.indexOf(ch) >= 0 && source.chars().filter(c -> c == ch).count()
                    != target.chars().filter(c -> c == ch).count()) return false;
        }
        java.util.regex.Pattern formats = java.util.regex.Pattern.compile("%[0-9.$,+\\-]*[a-zA-Z]");
        java.util.regex.Matcher left = formats.matcher(source);
        java.util.regex.Matcher right = formats.matcher(target);
        while (left.find()) {
            if (!right.find() || !left.group().equals(right.group())) return false;
        }
        return !right.find();
    }

    public static Tooltip tooltip(String source) {
        Tooltip tooltip = new Tooltip(source);
        watch(tooltip.textProperty());
        return tooltip;
    }

    public static Tooltip tooltip() {
        Tooltip tooltip = new Tooltip();
        watch(tooltip.textProperty());
        return tooltip;
    }

    public static void fillText(GraphicsContext gc, String source, double x, double y) {
        gc.fillText(text(source), x, y);
    }

    public static void fillText(GraphicsContext gc, String source, double x, double y, double maxWidth) {
        gc.fillText(text(source), x, y, maxWidth);
    }

    public static void strokeText(GraphicsContext gc, String source, double x, double y) {
        gc.strokeText(text(source), x, y);
    }

    public static void strokeText(GraphicsContext gc, String source, double x, double y, double maxWidth) {
        gc.strokeText(text(source), x, y, maxWidth);
    }

    public static void attach(Scene scene) {
        attach(scene.getRoot());
        scene.rootProperty().addListener((obs, oldRoot, root) -> attach(root));
        for (Window window : Window.getWindows()) attach(window);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) for (Window window : change.getAddedSubList()) attach(window);
        });
    }

    private static void attach(Window window) {
        if (!VISITED.add(window)) return;
        if (window instanceof Stage stage) watch(stage.titleProperty());
        if (window.getScene() != null) attach(window.getScene().getRoot());
        window.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) attach(scene.getRoot());
        });
    }

    private static void attach(Node node) {
        if (node == null || !VISITED.add(node)) return;
        watch(node.accessibleTextProperty());
        if (node instanceof Labeled label) watch(label.textProperty());
        if (node instanceof Text text) watch(text.textProperty());
        if (node instanceof TextInputControl input) watch(input.promptTextProperty());
        if (node instanceof Control control) {
            if (control.getTooltip() != null) watch(control.getTooltip().textProperty());
            control.tooltipProperty().addListener((obs, oldTip, tip) -> {
                if (tip != null) watch(tip.textProperty());
            });
        }
        if (node instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) attach(tab);
            tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
                while (change.next()) for (Tab tab : change.getAddedSubList()) attach(tab);
            });
        }
        if (node instanceof MenuButton button) {
            for (MenuItem item : button.getItems()) attach(item);
            button.getItems().addListener((ListChangeListener<MenuItem>) change -> {
                while (change.next()) for (MenuItem item : change.getAddedSubList()) attach(item);
            });
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) attach(child);
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) for (Node child : change.getAddedSubList()) attach(child);
            });
        }
    }

    private static void attach(Tab tab) {
        if (!VISITED.add(tab)) return;
        watch(tab.textProperty());
        attach(tab.getContent());
        tab.contentProperty().addListener((obs, old, content) -> attach(content));
    }

    private static void attach(MenuItem item) {
        if (!VISITED.add(item)) return;
        watch(item.textProperty());
        if (item instanceof Menu menu) {
            for (MenuItem child : menu.getItems()) attach(child);
            menu.getItems().addListener((ListChangeListener<MenuItem>) change -> {
                while (change.next()) for (MenuItem child : change.getAddedSubList()) attach(child);
            });
        }
    }

    private static void watch(Property<String> property) {
        if (VISITED.contains(property)) return;
        VISITED.add(property);
        SOURCES.put(property, property.getValue());
        property.addListener((obs, oldText, newText) -> {
            if (UPDATING.contains(property)) return;
            SOURCES.put(property, newText);
            display(property, newText);
        });
        display(property, property.getValue());
    }

    private static void display(Property<String> property, String source) {
        // Un texte lié (le libellé interne d'un MenuButton suit celui du bouton) reflète déjà une source traduite :
        // JavaFX refuse qu'on l'écrive.
        if (property.isBound()) return;
        String rendered = text(source);
        if (rendered == null || rendered.equals(property.getValue())) return;
        UPDATING.add(property);
        try { property.setValue(rendered); }
        finally { UPDATING.remove(property); }
    }

    private static Map<String, String> load(String path) {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream stream = I18n.class.getResourceAsStream(path)) {
            if (stream == null) return result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab < 0) continue;
                    result.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load translations: " + path, error);
        }
        return result;
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\t", "\t");
    }

    private static Map<String, String> fragments() {
        Map<String, String> sources = new LinkedHashMap<>(load("/i18n/en-fragments.tsv"));
        sources.putAll(load("/i18n/elements.tsv"));
        for (Map.Entry<String, String> entry : new ArrayList<>(sources.entrySet())) {
            String french = entry.getKey();
            String english = entry.getValue();
            if (Character.isLowerCase(french.charAt(0))) {
                sources.putIfAbsent(Character.toUpperCase(french.charAt(0)) + french.substring(1),
                        Character.toUpperCase(english.charAt(0)) + english.substring(1));
                sources.putIfAbsent(french.toUpperCase(Locale.ROOT), english.toUpperCase(Locale.ROOT));
            }
        }
        return sources;
    }

    private static Pattern fragmentPattern() {
        List<String> keys = new ArrayList<>(FRAGMENTS.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        List<String> alternatives = new ArrayList<>();
        for (String source : keys) {
            String boundaryStart = Character.isLetter(source.charAt(0)) ? "(?<!\\p{L})" : "";
            String boundaryEnd = Character.isLetter(source.charAt(source.length() - 1)) ? "(?!\\p{L})" : "";
            alternatives.add(boundaryStart + Pattern.quote(source) + boundaryEnd);
        }
        return Pattern.compile(alternatives.isEmpty() ? "(?!)" : String.join("|", alternatives));
    }
}
