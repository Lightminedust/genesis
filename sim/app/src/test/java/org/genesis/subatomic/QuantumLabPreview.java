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

/** Dessine le vrai écran subatomique sur une frame calculée par Python : la collision à des instants donnés, ou l'atelier. */
public final class QuantumLabPreview {
    public static void main(String[] args) throws Exception {
        UniverseFrame frame = UniverseFrame.parseFrom(Files.readAllBytes(Path.of(args[0])));
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        String[] instants = (args.length > 2 ? args[2] : "0").trim().split("\s+");
        String tab = args.length > 3 ? args[3] : "collision";
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                QuantumLab lab = new QuantumLab(new QuantumLab.Actions() {
                    @Override public void back() {}
                    @Override public void collide(int target, double energyMev, double angleDeg, int projectile) {}
                    @Override public void resample() {}
                    @Override public void test(int spinIndex, double[] triples) {}
                    @Override public void decay() {}
                    @Override public void clear() {}
                    @Override public void partons(boolean open) {}
                    @Override public void force(int channel) {}
                });
                Scene scene = new Scene(lab.getNode(), 1365, 768);
                scene.getStylesheets().add(QuantumLabPreview.class.getResource("/genesis.css").toExternalForm());
                lab.getNode().applyCss();
                lab.getNode().resize(1365, 768);
                lab.getNode().layout();
                lab.applyFrame(frame);
                if (tab.startsWith("bench")) {
                    lab.showBench();
                    String content = tab.contains(":") ? tab.substring(tab.indexOf(':') + 1) : "";
                    String pullSpec = content.contains("~") ? content.substring(content.indexOf('~') + 1) : "";
                    if (!pullSpec.isEmpty()) content = content.substring(0, content.indexOf('~'));
                    if (!content.isEmpty()) lab.bench().load(content);
                    if (!pullSpec.isEmpty()) {
                        // « ~i,dx » : tendre la corde du quark i de dx fm (le long de x)
                        String[] parts = pullSpec.split(",");
                        lab.bench().stretch(Integer.parseInt(parts[0]), new double[]{Double.parseDouble(parts[1]), 0, 0});
                    }
                    // l'assemblage chargé correspond à celui que le moteur a analysé
                    lab.bench().markTested();
                    lab.getNode().layout();
                }
                for (String instant : instants) {
                    double t = Double.parseDouble(instant);
                    if (tab.startsWith("bench")) {
                        for (int k = 0; k < (int) Math.round(t * 30); k++) lab.tick(1 / 30.0);
                    } else {
                        // l'instant est donné en secondes de récit ; on y arrive en jouant, pour que le cadrage suive
                        CollisionScene story = lab.collisionScene();
                        story.seekStory(Math.max(0, t - 1.5));
                        story.setPlaying(true);
                        long started = System.nanoTime();
                        CollisionScene.spaceNanos = CollisionScene.hadronNanos = 0;
                        for (int k = 0; k < 45; k++) lab.tick(1 / 30.0);
                        System.out.printf("t=%s : %.1f ms de calcul par image (espace %.1f, hadrons %.1f)%n", instant, (System.nanoTime() - started) / 45e6,
                                CollisionScene.spaceNanos / 45e6, CollisionScene.hadronNanos / 45e6);
                        story.setPlaying(false);
                    }
                    WritableImage image = scene.snapshot(null);
                    int w = (int) image.getWidth(), h = (int) image.getHeight();
                    int[] pixels = new int[w * h];
                    image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
                    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    out.setRGB(0, 0, w, h, pixels, 0, w);
                    ImageIO.write(out, "png", output.resolve(String.format("%s_%s.png", tab.replace(':', '_'), instant.replace('.', '_'))).toFile());
                }
            } catch (Throwable error) {
                error.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }
}
