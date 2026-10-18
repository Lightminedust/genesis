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
}
