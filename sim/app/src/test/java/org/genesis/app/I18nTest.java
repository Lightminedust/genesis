package org.genesis.app;

import org.genesis.common.Visuals;
import org.genesis.fusion.FusionDashboard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nTest {
    @Test
    void languageSwitchPreservesSymbolsAndEquations() {
        I18n.Language previous = I18n.language();
        try {
            I18n.setLanguage(I18n.Language.EN);
            assertEquals("Atom", I18n.text("Atome"));
            assertEquals("Gold", I18n.text("Or"));
            assertEquals("Au", I18n.text("Au"));
            assertEquals("e⁻", I18n.text("e⁻"));
            assertEquals("p⁺", I18n.text("p⁺"));
            assertEquals("1\u2009000", I18n.text("1\u2009000"));
            assertEquals(FusionDashboard.spaced("COLLISION CHAMBER"),
                    I18n.text(FusionDashboard.spaced("CHAMBRE DE COLLISION")));
            assertEquals("T H E    S U B A T O M I C    W O R L D",
                    I18n.text("L E    M O N D E    S U B A T O M I Q U E"));
            assertEquals("K = E − V = 4 MeV", I18n.text("K = E − V = 4 MeV"));
            assertEquals("1.5", Visuals.format(1.5));
            I18n.setLanguage(I18n.Language.FR);
            assertEquals("Atome", I18n.text("Atome"));
            assertEquals("Or", I18n.text("Or"));
            assertEquals("1,5", Visuals.format(1.5));
        } finally {
            I18n.setLanguage(previous);
        }
    }

    @Test
    void engineSentencesTranslateThroughTemplatesAndSegments() {
        I18n.Language previous = I18n.language();
        try {
            I18n.setLanguage(I18n.Language.EN);
            // Template slots: element names become English, numbers keep their digits, decimal commas become points.
            assertEquals("Iron-56 - Stable", I18n.text("Fer-56 - Stable"));
            assertEquals("Z = 26 -> Iron (Fe), transition metal.", I18n.text("Z = 26 -> Fer (Fe), métal de transition."));
            assertEquals("Measured half-life: 70 ms.", I18n.text("Demi-vie mesurée : 70 ms."));
            assertEquals("q = (8 x +e) + (8 x -e) = +0 [Neutral].", I18n.text("q = (8 x +e) + (8 x -e) = +0 [Neutre]."));
            // The pop-up left in French in English mode (reported by users).
            assertEquals("Connection to the Python engine lost. Retrying. Log: ",
                    I18n.text("Connexion au moteur Python interrompue. Nouvel essai en cours. Journal : "));
            // Segments joined by " · " are translated one by one.
            assertEquals("Es-254 → Bk-250: α decay", I18n.text("Es-254 → Bk-250 : désintégration α"));
            // Text that is already English is left alone.
            assertEquals("The proton leaves as one with the W's momentum.",
                    I18n.text("The proton leaves as one with the W's momentum."));
            I18n.setLanguage(I18n.Language.FR);
            assertEquals("Atome", I18n.text("Atome"));
            assertEquals("Or", I18n.text("Or"));
            assertEquals("1,5", Visuals.format(1.5));
        } finally {
            I18n.setLanguage(previous);
        }
    }
}
