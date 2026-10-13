package org.genesis.subatomic;

import com.studio.genesis.grpc.UniverseFrame;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/** Dessine la vraie vue de la mer de partons sur un film calculé par Python, à des fractions de sa durée (0 à 1). */
public final class PartonPreview {
    public static void main(String[] args) throws Exception {
        UniverseFrame frame = UniverseFrame.parseFrom(Files.readAllBytes(Path.of(args[0])));
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        String[] instants = (args.length > 2 ? args[2] : "0 .3 .6 1").trim().split("\s+");
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                PartonView view = new PartonView(new PartonView.Actions() {
                    @Override public void back() {}
                    @Override public void resolution(double q2, double xMin) {}
                    @Override public void lastStrike(double xMin) {}
                    @Override public void reset() {}
                });
                Scene scene = new Scene(view.getNode(), 1365, 768);
                scene.getStylesheets().add(PartonPreview.class.getResource("/genesis.css").toExternalForm());
                view.getNode().applyCss();
                view.getNode().resize(1365, 768);
                view.getNode().layout();
                view.applyFrame(frame);
                double duration = frame.getPartons().getDurationFmc();
                for (String instant : instants) {
                    double fraction = Double.parseDouble(instant);
                    long t0 = System.nanoTime();
                    for (int k = 0; k < 6; k++) { view.seek(fraction * duration); view.tick(1 / 30.0); }
                    System.out.printf("instant %s : %.1f ms de calcul par image%n", instant, (System.nanoTime() - t0) / 6e6);
                    WritableImage image = scene.snapshot(null);
                    int w = (int) image.getWidth(), h = (int) image.getHeight();
                    int[] pixels = new int[w * h];
                    image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
                    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    out.setRGB(0, 0, w, h, pixels, 0, w);
                    ImageIO.write(out, "png", output.resolve(String.format("p%03d.png", (int) Math.round(fraction * 100))).toFile());
                }
            } catch (Throwable error) {
                error.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }
}
