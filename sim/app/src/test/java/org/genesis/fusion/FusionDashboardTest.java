package org.genesis.fusion;

import java.util.List;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FusionDashboardTest {
    private static FusionDashboard.Board board(boolean plot, int equivalences) {
        var split = new FusionDashboard.Split("ÉNERGIE", "4 MeV", List.of(new FusionDashboard.Part("élan", "1 MeV", 1, Color.CYAN),
                new FusionDashboard.Part("tension", "3 MeV", 3, Color.ORANGE)));
        var dials = List.of(new FusionDashboard.Dial("a", "force", "5,8", "N", .5), new FusionDashboard.Dial("b", "distance", "31", "fm", .4));
        var curve = plot ? new FusionDashboard.Plot("V", new double[]{1, 2, 3}, new double[]{0, 4, 2}, 3, "E", 2, 2, 1, 3, -1, 5, "fm", "MeV") : null;
        List<String[]> eq = new java.util.ArrayList<>();
        for (int i = 0; i < equivalences; i++) eq.add(new String[]{"ligne " + i, "valeur"});
        return new FusionDashboard.Board("APPROCHE", split, dials, List.of(), List.of("F = 5 N", "V = 3 MeV"), curve, eq);
    }

    @Test void theCompactReadingIsShorterAndEveryExtraAddsHeight() {
        double base = FusionDashboard.height(board(false, 0), true);
        assertTrue(FusionDashboard.height(board(true, 0), true) == base, "compact : sans courbe ni tableau des lois");
        assertTrue(FusionDashboard.height(board(true, 0), false) > base + 150, "la courbe et les lois ajoutent leur place");
        assertTrue(FusionDashboard.height(board(false, 3), true) > base + 60, "les équivalences ajoutent leurs lignes, même en compact");
    }

    @Test void theCurveIsReadByLinearInterpolation() {
        var plot = board(true, 0).plot();
        assertEquals(2, FusionDashboard.interpolate(plot, 1.5), 1e-12);
        assertEquals(0, FusionDashboard.interpolate(plot, 0), "avant le premier point : la première valeur");
        assertEquals(2, FusionDashboard.interpolate(plot, 9), "après le dernier : la dernière valeur");
    }

    @Test void levelRunsFromTurquoiseThroughAmberToCoral() {
        assertTrue(FusionDashboard.level(0).getBlue() > FusionDashboard.level(0).getRed(), "au repos : froid");
        assertTrue(FusionDashboard.level(1).getRed() > FusionDashboard.level(1).getBlue(), "à saturation : chaud");
    }
}
