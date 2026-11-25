package org.genesis.app;

import org.genesis.common.Brand;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La marque de Genesis (assets/brand) : ses SVG lus tels quels, ses traits (des glitchs figés), ses icônes, le nom qui
 * s'écrit au démarrage, sur un écran qui couvre l'accueil dès sa première image.
 */
public class BrandingTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void theMarkIsAGAndItsCursor() {
        Brand.Art mark = Brand.MARK;
        assertEquals(2, mark.slots());                                   // G, _
        assertEquals(1, mark.layer(Brand.Role.INK).parts().size());
        assertEquals(1, mark.layer(Brand.Role.CURSOR).parts().get(0).slot());
        // Les franges : une pour le G, une pour le curseur, de chaque côté.
        for (Brand.Role fringe : new Brand.Role[]{Brand.Role.CYAN, Brand.Role.VIOLET}) {
            var parts = mark.layer(fringe).parts();
            assertEquals(2, parts.size());
            assertEquals(0, parts.get(0).slot());
            assertEquals(1, parts.get(1).slot());
        }
        // La frange cyan est à gauche du blanc, la violette à droite.
        double ink = mark.layer(Brand.Role.INK).parts().get(0).minX();
        assertTrue(mark.layer(Brand.Role.CYAN).parts().get(0).minX() < ink);
        assertTrue(mark.layer(Brand.Role.VIOLET).parts().get(0).minX() > ink);
    }

    @Test void theLogotypeIsSevenLettersAndACursor() {
        Brand.Art logotype = Brand.LOGOTYPE;
        assertEquals(8, logotype.slots());                               // G E N E S I S _
        var letters = logotype.layer(Brand.Role.INK).parts();
        assertEquals(7, letters.size());
        for (int k = 0; k < letters.size(); k++) assertEquals(k, letters.get(k).slot());
        assertEquals(7, logotype.layer(Brand.Role.CURSOR).parts().get(0).slot());
        // Une police à chasse fixe : les cases sont régulières (à quelques unités près).
        double advance = (logotype.layer(Brand.Role.CURSOR).parts().get(0).minX() - letters.get(0).minX()) / 7;
        assertEquals(660, advance, 15);
        assertEquals(750, logotype.height(), 1);
    }

    @Test void theNameIsTypedLetterByLetter() {
        assertEquals(0, StartupScreen.typed(0.3));                       // le curseur seul
        assertEquals(1, StartupScreen.typed(StartupScreen.BOOT_AT));     // le G_
        assertEquals(1, StartupScreen.typed(StartupScreen.KEYS[0] - .01));
        assertEquals(7, StartupScreen.typed(StartupScreen.KEYS[5]));     // GENESIS_
        assertTrue(StartupScreen.KEYS[5] < StartupScreen.MINIMUM_S - 2, "le nom est écrit bien avant la sortie");
    }

    @Test void theCursorBlinksLikeATerminal() {
        assertTrue(Brand.cursorOn(0));
        assertFalse(Brand.cursorOn(Brand.BLINK_S * .75));
        assertTrue(Brand.cursorOn(Brand.BLINK_S * 1.1));
    }

    @Test void theFringesAreTheWhiteShifted() {
        assertEquals(-14, Brand.MARK.cyanDx(), .5);
        assertEquals(14, Brand.MARK.violetDx(), .5);
        assertEquals(-12, Brand.LOGOTYPE.cyanDx(), .5);
        assertEquals(12, Brand.LOGOTYPE.violetDx(), .5);
    }

    @Test void theTraitsOfTheMarkAreAFrozenGlitch() throws Exception {
        onFx(() -> {
            // Refait à partir du blanc, ses trois tranches de franges glissées, le G_ est celui du SVG, au pixel près.
            int[] kit = render(null), rebuilt = render(Brand.MARK_TRAITS), clean = render(List.of());
            assertTrue(difference(kit, rebuilt) < .002, "le G_ refait : " + difference(kit, rebuilt));
            // Sans ses tranches, ce n'est plus le G_ du kit : ses traits sont bien ces glitchs.
            assertTrue(difference(kit, clean) > .005, "sans ses traits : " + difference(kit, clean));
        });
    }

    @Test void theMarkGlitchesInShortBursts() {
        List<Brand.Slice> first = Brand.glitchAt(Brand.MARK, .01);
        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertEquals(first, Brand.glitchAt(Brand.MARK, .01));            // le même instant, le même glitch
        assertNull(Brand.glitchAt(Brand.MARK, -.1));
        assertNull(Brand.glitchAt(Brand.MARK, 1));                       // au repos : la marque telle que dessinée
        int glitched = 0, bursts = 0;
        boolean was = false;
        for (int k = 0; k < 60_000; k++) {
            boolean on = Brand.glitchAt(Brand.MARK, k / 1000.0) != null;
            if (on) glitched++;
            if (on && !was) bursts++;
            was = on;
        }
        assertEquals(19, bursts);                                        // une bouffée toutes les 3,2 s
        assertTrue(glitched / 60_000.0 < .1, "glitché " + glitched / 600.0 + " % du temps");
    }

    @Test void theStartupCoversTheMenuFromItsFirstImage() throws Exception {
        onFx(() -> {
            StartupScreen startup = new StartupScreen();
            startup.show(url -> { }, null);
            assertTrue(startup.getNode().isVisible());
            assertEquals(1, startup.getNode().getOpacity(), 1e-12);     // avant toute image : l'accueil ne se voit pas
            assertEquals(0, startup.taglineForTest().getOpacity(), 1e-12); // et la phrase n'arrive qu'une fois le nom écrit
            startup.tick(1 / 60.0);
            assertEquals(1, startup.getNode().getOpacity(), 1e-12);
        });
    }

    private static void onFx(Runnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(body, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /** Le G_ dessiné sur fond noir (null : tel que son SVG le dessine). */
    private static int[] render(List<Brand.Slice> glitch) {
        Brand.Art art = Brand.MARK;
        double s = .3;
        Canvas canvas = new Canvas(Math.ceil(art.width() * s) + 60, Math.ceil(art.height() * s) + 40);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.translate(30 - art.minX() * s, 20 - art.minY() * s);
        g.scale(s, s);
        Brand.draw(g, art, slot -> 1, glitch, null);
        WritableImage image = canvas.snapshot(null, null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] px = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return px;
    }

    /** La part des pixels qui changent nettement (un canal de plus de 48 sur 255). */
    private static double difference(int[] a, int[] b) {
        int changed = 0;
        for (int k = 0; k < a.length; k++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                if (Math.abs((a[k] >> shift & 255) - (b[k] >> shift & 255)) > 48) {
                    changed++;
                    break;
                }
            }
        }
        return changed / (double) a.length;
    }

    @Test void theWindowIconsAreTheNewOnes() {
        assertNotNull(Branding.logo());
        assertEquals(256, Branding.logo().getWidth(), 0);
        assertNotNull(Branding.class.getResource("/brand/web/favicon-16.png"));
    }
}
