package org.genesis;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javax.imageio.ImageIO;

/** Aperçu de Genesis dans sa boule : ses états (une planche), puis son corps qui se sépare et se recolle (une bande). */
public final class MascotPreview {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "../../artifacts/mascot.png");
        double size = args.length > 1 ? Double.parseDouble(args[1]) : 300;
        Files.createDirectories(out.toAbsolutePath().getParent());
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                int w = (int) size, h = (int) Math.round(size * 1.22);
                GenesisMascot m = new GenesisMascot(w, h);
                m.setManual(true);
                StackPane holder = new StackPane(m.getNode());
                holder.setStyle("-fx-background-color: #0B1120;");
                Scene scene = new Scene(holder, w, h);
                for (int k = 0; k < 600; k++) m.tick(1 / 30.0);                    // elle a fini de se présenter
                String[] states = {"IDLE", "TALKING", "IDEA", "HAPPY", "CURIOUS", "EXCITED", "SLEEPY"};
                BufferedImage sheet = new BufferedImage(w * 4, h * 2, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < states.length; i++) {
                    if (states[i].equals("TALKING")) {
                        m.setMood(GenesisMascot.Mood.IDLE);
                        m.say("Un photon s'échappe ! L'électron redescend d'un niveau et l'écart d'énergie part en lumière.", "", GenesisMascot.Mood.IDLE);
                        for (int k = 0; k < 20; k++) m.tick(1 / 30.0);
                    } else {
                        for (int k = 0; k < 400; k++) m.tick(1 / 30.0);
                        m.setMood(GenesisMascot.Mood.valueOf(states[i]));
                        for (int k = 0; k < (states[i].equals("SLEEPY") ? 120 : 25); k++) m.tick(1 / 30.0);
                    }
                    snap(scene, sheet, (i % 4) * w, (i / 4) * h, w, h);
                }
                ImageIO.write(sheet, "png", out.toFile());
                // la bande : 8 instants à 1,6 s d'écart — le corps se sépare et se recolle
                m.setMood(GenesisMascot.Mood.IDLE);
                BufferedImage film = new BufferedImage(w * 8, h, BufferedImage.TYPE_INT_ARGB);
                StringBuilder pieces = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    for (int k = 0; k < 48; k++) m.tick(1 / 30.0);
                    snap(scene, film, i * w, 0, w, h);
                    pieces.append(m.pieces()).append(' ');
                }
                System.out.println("morceaux : " + pieces);
                ImageIO.write(film, "png", out.resolveSibling(out.getFileName().toString().replace(".png", "_film.png")).toFile());
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    private static void snap(Scene scene, BufferedImage into, int x, int y, int w, int h) {
        WritableImage image = scene.snapshot(null);
        int[] px = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        into.setRGB(x, y, w, h, px, 0, w);
    }
}
