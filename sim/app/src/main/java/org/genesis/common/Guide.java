package org.genesis.common;

import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Le guide d'un laboratoire : ce qu'on y fait en une phrase, par où commencer, ce que montre chaque zone de l'écran et
 * ses touches. Chaque laboratoire écrit le sien (avec ses propres zones) ; l'application le montre par-dessus l'écran,
 * à la demande (touche H) et la première fois qu'on ouvre ce laboratoire. C'est là que vont les explications de
 * l'interface : les panneaux, eux, ne gardent que les noms et les valeurs.
 */
public final class Guide {
    /** Une zone de l'écran : le nœud qui l'occupe, son nom, ce qu'on y fait (une ou deux phrases). */
    public record Spot(Node node, String title, String text) { }

    /** Une touche et ce qu'elle fait. */
    public record Key(String key, String action) { }

    private final String lab, lead;
    private final List<String> steps = new ArrayList<>();
    private final List<Spot> spots = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();

    public Guide(String lab, String lead) {
        this.lab = lab;
        this.lead = lead;
    }

    /** Une étape de « par où commencer », dans l'ordre. */
    public Guide step(String text) {
        steps.add(text);
        return this;
    }

    /** Une zone (ignorée si le nœud manque ou n'est pas affiché). */
    public Guide spot(Node node, String title, String text) {
        if (node != null) spots.add(new Spot(node, title, text));
        return this;
    }

    public Guide key(String key, String action) {
        keys.add(new Key(key, action));
        return this;
    }

    public String lab() { return lab; }
    public String lead() { return lead; }
    public List<String> steps() { return steps; }
    public List<Spot> spots() { return spots; }
    public List<Key> keys() { return keys; }
}
