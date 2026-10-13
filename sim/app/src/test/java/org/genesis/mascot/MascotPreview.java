package org.genesis.mascot;

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
                snap(scene, new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), 0, 0, w, h);
                for (int k = 0; k < 300; k++) m.tick(1 / 30.0);
                long start = System.nanoTime();
                for (int k = 0; k < 300; k++) m.tick(1 / 30.0);                    // elle a fini de se présenter
                System.out.printf("une image : %.2f ms%n", (System.nanoTime() - start) / 300e6);
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
                // une bande serrée : 12 instants à 0,2 s d'écart, pour juger la continuité du mouvement (un demi-tour compris)
                BufferedImage close = new BufferedImage(w * 12, h, BufferedImage.TYPE_INT_ARGB);
                for (int k = 0; k < 150; k++) m.tick(1 / 30.0);
                for (int i = 0; i < 12; i++) {
                    for (int k = 0; k < 6; k++) m.tick(1 / 30.0);
                    snap(scene, close, i * w, 0, w, h);
                }
                ImageIO.write(close, "png", out.resolveSibling(out.getFileName().toString().replace(".png", "_close.png")).toFile());
                // la bande : 8 instants à 0,8 s d'écart — elle nage
                m.setMood(GenesisMascot.Mood.IDLE);
                BufferedImage film = new BufferedImage(w * 8, h, BufferedImage.TYPE_INT_ARGB);
                StringBuilder pieces = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    for (int k = 0; k < 24; k++) m.tick(1 / 30.0);
                    snap(scene, film, i * w, 0, w, h);
                    pieces.append(m.pieces()).append(' ');
                }
                System.out.println("morceaux : " + pieces);
                ImageIO.write(film, "png", out.resolveSibling(out.getFileName().toString().replace(".png", "_film.png")).toFile());
                // la souris : elle se pose à droite (elle vient), passe à gauche (elle suit), s'agite près d'elle (elle fuit), s'en va
                BufferedImage follow = new BufferedImage(w * 8, h, BufferedImage.TYPE_INT_ARGB);
                double[][] where = {{.72, .3}, {.72, .3}, {.72, .3}, {.26, .46}, {.26, .46}, {.26, .46}, {.3, .46}, {-1, -1}};
                for (int i = 0; i < 8; i++) {
                    for (int k = 0; k < 26; k++) {
                        double x = where[i][0] * w + (i == 6 && k < 8 ? (k % 2 == 0 ? .12 : -.12) * w : 0), y = where[i][1] * h;
                        javafx.event.EventType<javafx.scene.input.MouseEvent> type = where[i][0] < 0 ? javafx.scene.input.MouseEvent.MOUSE_EXITED : javafx.scene.input.MouseEvent.MOUSE_MOVED;
                        javafx.event.Event.fireEvent(m.getNode(), new javafx.scene.input.MouseEvent(type, x, y, x, y, javafx.scene.input.MouseButton.NONE, 0,
                                false, false, false, false, false, false, false, true, false, false, null));
                        m.tick(1 / 30.0);
                    }
                    snap(scene, follow, i * w, 0, w, h);
                }
                ImageIO.write(follow, "png", out.resolveSibling(out.getFileName().toString().replace(".png", "_follow.png")).toFile());
                javafx.event.Event.fireEvent(m.getNode(), new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_EXITED, 0, 0, 0, 0,
                        javafx.scene.input.MouseButton.NONE, 0, false, false, false, false, false, false, false, true, false, false, null));
                for (int k = 0; k < 120; k++) m.tick(1 / 30.0);
                // un clic : elle se change en matière noire, se divise, se rassemble, se reconstitue — 8 instants à 0,95 s d'écart
                m.poke();
                BufferedImage split = new BufferedImage(w * 8, h, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < 8; i++) {
                    for (int k = 0; k < (i == 0 ? 14 : 29); k++) m.tick(1 / 30.0);
                    snap(scene, split, i * w, 0, w, h);
                }
                ImageIO.write(split, "png", out.resolveSibling(out.getFileName().toString().replace(".png", "_split.png")).toFile());
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
