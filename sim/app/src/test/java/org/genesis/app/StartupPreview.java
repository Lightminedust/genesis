package org.genesis.app;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/**
 * L'écran de démarrage, sans fenêtre ni moteur : StartupPreview dossier [largeur hauteur] [old].
 * Trois images : au début, quelques secondes plus tard (une autre orbitale), et un démarrage impossible ;
 * « old » : l'ancien écran d'attente avec son cadre de soutien, pour comparer.
 */
public final class StartupPreview {
    private static long now = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        int width = args.length > 2 ? Integer.parseInt(args[1]) : 1440, height = args.length > 2 ? Integer.parseInt(args[2]) : 900;
        boolean old = args.length > 3 && args[3].equals("old") || args.length == 2 && args[1].equals("old");
        if (System.getProperty("genesis.lang") != null) {
            I18n.setLanguage(I18n.Language.valueOf(System.getProperty("genesis.lang").toUpperCase(java.util.Locale.ROOT)));
        }
        Platform.startup(() -> { });
        Platform.runLater(() -> {
            try {
                App app = new App();
                Scene scene = app.buildScene(width, height);
                scene.snapshot(null);
                String tag = "_" + width + (I18n.language() == I18n.Language.EN ? "_en" : "");
                if (args.length > 3 && args[3].equals("notice")) {
                    app.previewTelemetryNotice(true);
                    run(app, 2.0);
                    ImageIO.write(snap(scene), "png", out.resolve("notice" + tag + ".png").toFile());
                    app.previewTelemetryNotice(false);
                    run(app, 1.0);
                    ImageIO.write(snap(scene), "png", out.resolve("notice_review" + tag + ".png").toFile());
                    return;
                }
                if (old) {
                    app.previewLoader();
                    run(app, 1.2);
                    ImageIO.write(snap(scene), "png", out.resolve("startup_old" + tag + ".png").toFile());
                    return;
                }
                app.previewStartup(false);
                run(app, 1.5);
                ImageIO.write(snap(scene), "png", out.resolve("startup" + tag + ".png").toFile());
                run(app, 6.0);
                ImageIO.write(snap(scene), "png", out.resolve("startup_later" + tag + ".png").toFile());
                app.previewStartup(true);
                run(app, 0.8);
                ImageIO.write(snap(scene), "png", out.resolve("startup_failed" + tag + ".png").toFile());
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    private static void run(App app, double seconds) {
        for (int k = 0; k < Math.round(seconds * 60); k++) {
            now += 16_666_667L;
            app.tickForPreview(now);
        }
    }

    private static BufferedImage snap(Scene scene) {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] px = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }
}
