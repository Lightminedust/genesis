package org.genesis.molecule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Le texte des flèches du schéma : les chiffres d'une formule en indices, pas ceux d'une température. */
class SchemeEditorTest {
    @Test void formulasOnArrowsGetSubscripts() {
        assertEquals("Co₂(CO)₈", SchemeEditor.chem("Co2(CO)8"));
        assertEquals("H₂SO₄", SchemeEditor.chem("H2SO4"));
        assertEquals("MeI", SchemeEditor.chem("MeI"));
        assertEquals("THF, 25 °C", SchemeEditor.chem("THF, 25 °C"));
        assertEquals("2 MeI", SchemeEditor.chem("2 MeI"));
    }
}
