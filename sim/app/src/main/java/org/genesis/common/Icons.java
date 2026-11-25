package org.genesis.common;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

import java.util.Map;

/**
 * Les icônes de Genesis : un seul dessin, au trait (1,5 px, bouts ronds), sur une grille de 16 px — pas de pictogrammes
 * de police ni d'émojis, qui changent d'un système à l'autre. Leur couleur vient de la feuille de style (.icon) : elles
 * prennent l'encre du bouton qui les porte, et s'éclairent avec lui au survol.
 */
public final class Icons {
    private Icons() { }

    /** Le dessin de chaque icône (chemins SVG sur 16 × 16). Les noms finissant par « * » sont pleins, pas au trait. */
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("home", "M2.5 7.2 L8 2.8 L13.5 7.2 M4 6 L4 13.2 L12 13.2 L12 6 M6.6 13.2 L6.6 9.6 L9.4 9.6 L9.4 13.2"),
            Map.entry("help", "M1.75 8 A6.25 6.25 0 1 0 14.25 8 A6.25 6.25 0 1 0 1.75 8 Z M6.1 6.3 C6.1 5 7 4.4 8 4.4 "
                    + "C9.1 4.4 9.9 5.1 9.9 6.1 C9.9 7.4 8 7.6 8 9.1 M8 11.3 L8 11.4"),
            Map.entry("camera", "M2 5.6 L2 12.6 L14 12.6 L14 5.6 L11.1 5.6 L10 3.8 L6 3.8 L4.9 5.6 Z M5.7 8.9 "
                    + "A2.3 2.3 0 1 0 10.3 8.9 A2.3 2.3 0 1 0 5.7 8.9 Z"),
            Map.entry("expand", "M2.5 6 L2.5 2.5 L6 2.5 M10 2.5 L13.5 2.5 L13.5 6 M13.5 10 L13.5 13.5 L10 13.5 M6 13.5 L2.5 13.5 L2.5 10"),
            Map.entry("contract", "M6 2.5 L6 6 L2.5 6 M13.5 6 L10 6 L10 2.5 M10 13.5 L10 10 L13.5 10 M2.5 10 L6 10 L6 13.5"),
            Map.entry("panels", "M2 3 L14 3 L14 13 L2 13 Z M5.5 3 L5.5 13 M10.5 3 L10.5 13"),
            Map.entry("settings", "M2.5 4.5 L3.9 4.5 M7.1 4.5 L13.5 4.5 M3.9 4.5 A1.6 1.6 0 1 0 7.1 4.5 A1.6 1.6 0 1 0 3.9 4.5 "
                    + "M2.5 8 L8.9 8 M12.1 8 L13.5 8 M8.9 8 A1.6 1.6 0 1 0 12.1 8 A1.6 1.6 0 1 0 8.9 8 "
                    + "M2.5 11.5 L5.4 11.5 M8.6 11.5 L13.5 11.5 M5.4 11.5 A1.6 1.6 0 1 0 8.6 11.5 A1.6 1.6 0 1 0 5.4 11.5"),
            Map.entry("globe", "M1.75 8 A6.25 6.25 0 1 0 14.25 8 A6.25 6.25 0 1 0 1.75 8 M1.75 8 L14.25 8 "
                    + "M8 1.75 C5.6 4 5.6 12 8 14.25 M8 1.75 C10.4 4 10.4 12 8 14.25"),
            Map.entry("play*", "M5 3.2 L13 8 L5 12.8 Z"),
            Map.entry("pause", "M5.5 3.5 L5.5 12.5 M10.5 3.5 L10.5 12.5"),
            Map.entry("step-forward", "M3.5 3.6 L9.8 8 L3.5 12.4 Z M12.5 3.5 L12.5 12.5"),
            Map.entry("step-back", "M12.5 3.6 L6.2 8 L12.5 12.4 Z M3.5 3.5 L3.5 12.5"),
            Map.entry("replay", "M3.2 8 A4.8 4.8 0 1 0 4.6 4.6 M4.6 1.8 L4.6 4.6 L7.4 4.6"),
            Map.entry("plus", "M8 3.5 L8 12.5 M3.5 8 L12.5 8"),
            Map.entry("minus", "M3.5 8 L12.5 8"),
            Map.entry("orbit", "M13.2 8 A5.2 5.2 0 1 1 11.7 4.3 M11.8 1.7 L11.8 4.4 L9.1 4.4"),
            Map.entry("move", "M8 1.8 L8 14.2 M1.8 8 L14.2 8 M6.2 3.6 L8 1.8 L9.8 3.6 M6.2 12.4 L8 14.2 L9.8 12.4 "
                    + "M3.6 6.2 L1.8 8 L3.6 9.8 M12.4 6.2 L14.2 8 L12.4 9.8"),
            Map.entry("target", "M3.5 8 A4.5 4.5 0 1 0 12.5 8 A4.5 4.5 0 1 0 3.5 8 M8 1.5 L8 4 M8 12 L8 14.5 M1.5 8 L4 8 M12 8 L14.5 8"),
            Map.entry("close", "M4 4 L12 12 M12 4 L4 12"),
            Map.entry("chevron-down", "M4 6 L8 10 L12 6"),
            Map.entry("chevron-up", "M4 10 L8 6 L12 10"),
            Map.entry("chevron-right", "M6 4 L10 8 L6 12"),
            Map.entry("chevron-left", "M10 4 L6 8 L10 12"),
            Map.entry("external", "M9 2.5 L13.5 2.5 L13.5 7 M13.5 2.5 L7.5 8.5 M11.5 9.5 L11.5 13.5 L2.5 13.5 L2.5 4.5 L6.5 4.5"),
            Map.entry("info", "M1.75 8 A6.25 6.25 0 1 0 14.25 8 A6.25 6.25 0 1 0 1.75 8 Z M8 7.2 L8 11.5 M8 4.7 L8 4.8"),
            Map.entry("star", "M8.00 2.10 L9.59 6.22 L13.99 6.45 L10.57 9.23 L11.70 13.50 L8.00 11.10 L4.30 13.50 L5.43 9.23 "
                    + "L2.01 6.45 L6.41 6.22 Z"),
            Map.entry("book", "M2.5 3.6 C4.4 3 6.6 3.1 8 4.3 C9.4 3.1 11.6 3 13.5 3.6 L13.5 12.9 C11.6 12.3 9.4 12.4 8 13.6 "
                    + "C6.6 12.4 4.4 12.3 2.5 12.9 Z M8 4.3 L8 13.6"),
            Map.entry("arrow-right", "M2.5 8 L13.5 8 M9.5 4 L13.5 8 L9.5 12"),
            Map.entry("arrow-left", "M13.5 8 L2.5 8 M6.5 4 L2.5 8 L6.5 12"),
            Map.entry("layers", "M8 2.5 L14 5.5 L8 8.5 L2 5.5 Z M2 8.5 L8 11.5 L14 8.5 M2 11.2 L8 14.2 L14 11.2"),
            Map.entry("glasses", "M1.6 9.4 A2.5 2.5 0 1 0 6.6 9.4 A2.5 2.5 0 1 0 1.6 9.4 Z M9.4 9.4 A2.5 2.5 0 1 0 14.4 9.4 "
                    + "A2.5 2.5 0 1 0 9.4 9.4 Z M6.6 9 C7.3 8.2 8.7 8.2 9.4 9 M1.7 8.8 L2.9 5.4 M14.3 8.8 L13.1 5.4"),
            Map.entry("eye", "M1.5 8 C3.5 4.2 12.5 4.2 14.5 8 C12.5 11.8 3.5 11.8 1.5 8 Z M5.9 8 A2.1 2.1 0 1 0 10.1 8 "
                    + "A2.1 2.1 0 1 0 5.9 8"),
            Map.entry("spark", "M8 1.5 L8 5 M8 11 L8 14.5 M1.5 8 L5 8 M11 8 L14.5 8 M3.4 3.4 L5.5 5.5 M10.5 10.5 L12.6 12.6 "
                    + "M12.6 3.4 L10.5 5.5 M5.5 10.5 L3.4 12.6"),
            Map.entry("table", "M2 3.5 L5.5 3.5 L5.5 7 L2 7 Z M10.5 3.5 L14 3.5 L14 7 L10.5 7 Z M2 9 L14 9 L14 12.5 L2 12.5 Z "
                    + "M6 9 L6 12.5 M10 9 L10 12.5"),
            Map.entry("mail", "M2 4 L14 4 L14 12 L2 12 Z M2.4 4.5 L8 9 L13.6 4.5"),
            Map.entry("download", "M8 2.5 L8 10.5 M4.8 7.3 L8 10.5 L11.2 7.3 M3 13.5 L13 13.5"),
            Map.entry("folder", "M2 4 L6.2 4 L7.5 5.5 L14 5.5 L14 12.8 L2 12.8 Z"),
            Map.entry("flask", "M6.2 2.5 L9.8 2.5 M6.8 2.5 L6.8 6.6 L2.9 12.6 C2.6 13.1 3 13.6 3.5 13.6 L12.5 13.6 "
                    + "C13 13.6 13.4 13.1 13.1 12.6 L9.2 6.6 L9.2 2.5 M4.6 10 L11.4 10"),
            Map.entry("more", "M3.5 8 L3.6 8 M8 8 L8.1 8 M12.5 8 L12.6 8"),
            Map.entry("check", "M3 8.4 L6.4 11.6 L13 4.6"),
            Map.entry("chat", "M2.5 3.5 L13.5 3.5 L13.5 10.5 L8.6 10.5 L5.4 13.2 L5.4 10.5 L2.5 10.5 Z"),
            Map.entry("keyboard", "M1.8 4.5 L14.2 4.5 L14.2 11.5 L1.8 11.5 Z M4 7 L4.1 7 M6.6 7 L6.7 7 M9.3 7 L9.4 7 M12 7 L12.1 7 "
                    + "M5 9.4 L11 9.4"),
            Map.entry("spectrum", "M1.5 12 C3.5 12 3.8 4 5.5 4 C7.2 4 7.3 12 9 12 C10.6 12 10.8 6 12.2 6 C13.4 6 13.6 10 14.5 10"),
            // La vue du chemin des schémas : étiquettes, charges, flèches courbes, trajectoires, hydrogènes.
            Map.entry("tag", "M2.5 3.5 L8.2 3.5 L13.5 8.8 L8.8 13.5 L3.5 8.2 Z M5.7 5.7 L5.8 5.7"),
            Map.entry("charge", "M2.5 5.5 L7.5 5.5 M5 3 L5 8 M8.5 11 L13.5 11"),
            Map.entry("curly", "M3 13 C2.6 6.2 8.4 3 12.6 6.2 M9.9 6.9 L12.8 6.4 L12.6 3.5"),
            Map.entry("trail", "M2.6 12.6 L2.7 12.6 M5.3 10.6 L5.4 10.6 M8 8.7 L8.1 8.7 M10.6 6.6 L10.7 6.6 "
                    + "M11.9 4.2 A1.6 1.6 0 1 0 15.1 4.2 A1.6 1.6 0 1 0 11.9 4.2"),
            Map.entry("hydrogen", "M4.5 3 L4.5 13 M11.5 3 L11.5 13 M4.5 8 L11.5 8")
    );

    /** L'icône `name`, à la taille `size` (px), sous la classe CSS « icon » (et « solid » pour une icône pleine). */
    public static Node path(String name, double size) {
        boolean solid = !PATHS.containsKey(name) && PATHS.containsKey(name + "*");
        String data = PATHS.getOrDefault(name, PATHS.getOrDefault(name + "*", PATHS.get("more")));
        SVGPath path = new SVGPath();
        path.setContent(data);
        path.getStyleClass().add("icon");
        if (solid) path.getStyleClass().add("solid");
        // Une boîte invisible de 16 × 16 : toutes les icônes occupent la même place, quel que soit leur dessin.
        Rectangle box = new Rectangle(0, 0, 16, 16);
        box.setFill(Color.TRANSPARENT);
        box.setStroke(null);
        box.setMouseTransparent(true);
        double s = size / 16;
        if (s != 1) {
            path.getTransforms().add(new Scale(s, s, 0, 0));
            box.getTransforms().add(new Scale(s, s, 0, 0));
        }
        Group group = new Group(box, path);
        group.getStyleClass().add("icon-box");
        return group;
    }

    /** La marque de Genesis, le G_ (assets/brand, ses tracés exacts : voir Brand), haute de `size` px. */
    public static Node logo(double size) {
        Node mark = Brand.node(Brand.MARK, size);
        mark.getStyleClass().add("logo");
        return mark;
    }

    /** Les noms connus (pour les tests). */
    static java.util.Set<String> names() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String k : PATHS.keySet()) out.add(k.endsWith("*") ? k.substring(0, k.length() - 1) : k);
        return out;
    }
}
