package org.genesis.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le style commun : les légendes remises en phrases, les icônes. */
class PlanTest {
    @Test
    void captionsInCapitalsBecomeSentencesAndKeepTheirNamesAndSymbols() {
        assertEquals("Le mur à franchir", Plan.sentence("LE MUR À FRANCHIR"));
        assertEquals("Le plasma · Lawson", Plan.sentence("LE PLASMA · LAWSON"));
        assertEquals("Noyau A", Plan.sentence("NOYAU A"));
        assertEquals("Résolution Q²", Plan.sentence("RÉSOLUTION Q²"));
        assertEquals("Approche · l'élan devient répulsion", Plan.sentence("APPROCHE · L'ÉLAN DEVIENT RÉPULSION"));
        assertEquals("Effet tunnel (WKB)", Plan.sentence("EFFET TUNNEL (WKB)"));
        // Ce qui a déjà des minuscules ne change pas ; un symbole seul non plus.
        assertEquals("Déjà une phrase", Plan.sentence("Déjà une phrase"));
        assertEquals("²³⁵U", Plan.sentence("²³⁵U"));
        assertEquals("MeV", Plan.sentence("MeV"));
    }

    @Test
    void everyIconHasADrawingAndTheSameBox() {
        assertTrue(Icons.names().size() >= 30);
        for (String name : Icons.names()) {
            assertNotNull(Icons.path(name, 16), name);
            assertEquals(16, Icons.path(name, 16).getLayoutBounds().getWidth(), 1.5, name);
        }
        // Une icône inconnue ne casse rien : elle prend le dessin par défaut.
        assertNotNull(Icons.path("introuvable", 16));
        assertEquals(12, Icons.path("play", 12).getLayoutBounds().getWidth(), 1.5);
    }
}
