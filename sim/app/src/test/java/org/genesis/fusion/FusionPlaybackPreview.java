package org.genesis.fusion;

import com.studio.genesis.grpc.UniverseFrame;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Records the real view while its playback clock advances, for visual review. */
public final class FusionPlaybackPreview {
    public static void main(String[] args) throws Exception {
        UniverseFrame frame = UniverseFrame.parseFrom(Files.readAllBytes(Path.of(args[0])));
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        double duration = args.length > 2 ? Double.parseDouble(args[2]) : 24;
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                ReactorView view = new ReactorView(() -> {}, i -> {}, k -> {}, () -> {});
                Scene scene = new Scene(view.getNode(), 1365, 768);
                scene.getStylesheets().add(FusionPlaybackPreview.class.getResource("/genesis.css").toExternalForm());
                view.getNode().applyCss();
                view.getNode().resize(1365, 768);
                view.getNode().layout();
                Scene near = new Scene(view.resultNode(), 472, 568, javafx.scene.paint.Color.web("#0A121E"));
                near.getStylesheets().add(FusionPlaybackPreview.class.getResource("/genesis.css").toExternalForm());
                // Une sonde du mode Inspecter : « image:x:y » (image enregistrée n°, point de la scène).
                String[] probe = args.length > 3 && !args[3].isBlank() ? args[3].split(":") : null;
                view.resultNode().resize(472, 568);
                view.resultNode().layout();
                view.applyFrame(frame);
                int count = 0;
                long start = System.nanoTime();
                for (int step = 0; step <= Math.ceil(duration * 30); step++) {
                    if (probe != null && step == Integer.parseInt(probe[0]) * 6)
                        view.inspectAt(Double.parseDouble(probe[1]), Double.parseDouble(probe[2]));
                    view.tick(1.0 / 30);
                    if (step % 6 != 0) continue;
                    WritableImage image = scene.snapshot(null);
                    int w = (int) image.getWidth(), h = (int) image.getHeight();
                    int[] pixels = new int[w * h];
                    image.getPixelReader().getPixels(0, 0, w, h,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
                    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    out.setRGB(0, 0, w, h, pixels, 0, w);
                    ImageIO.write(out, "png", output.resolve(String.format("%04d.png", count)).toFile());
                    WritableImage close = near.snapshot(null);
                    int cw = (int) close.getWidth(), ch = (int) close.getHeight();
                    int[] closePixels = new int[cw * ch];
                    close.getPixelReader().getPixels(0, 0, cw, ch, javafx.scene.image.PixelFormat.getIntArgbInstance(), closePixels, 0, cw);
                    BufferedImage closeOut = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
                    closeOut.setRGB(0, 0, cw, ch, closePixels, 0, cw);
                    ImageIO.write(closeOut, "png", output.resolve(String.format("c%04d.png", count++)).toFile());
                }
                // Un clic sur le neutron le plus loin : la vue doit s'en approcher, sans déplacer le centre.
                view.seekProgress(1);
                for (int k = 0; k < 6; k++) view.tick(1.0 / 30);
                view.focusMapOn(0, 1);
                for (int k = 0; k < 60; k++) view.tick(1.0 / 30);
                {
                    WritableImage zoomed = scene.snapshot(null);
                    int zw = (int) zoomed.getWidth(), zh = (int) zoomed.getHeight();
                    int[] zp = new int[zw * zh];
                    zoomed.getPixelReader().getPixels(0, 0, zw, zh, javafx.scene.image.PixelFormat.getIntArgbInstance(), zp, 0, zw);
                    BufferedImage zo = new BufferedImage(zw, zh, BufferedImage.TYPE_INT_ARGB);
                    zo.setRGB(0, 0, zw, zh, zp, 0, zw);
                    ImageIO.write(zo, "png", output.resolve("zoomed.png").toFile());
                }
                view.focusMapOn(-1, -1);
                for (int k = 0; k < 60; k++) view.tick(1.0 / 30);
                // La lecture complète : toutes les métriques.
                javafx.scene.Node fullButton = view.getNode().lookup("#collision-view-full");
                if (fullButton instanceof javafx.scene.control.ToggleButton toggleFull) {
                    toggleFull.fire();
                    view.getNode().applyCss();
                    view.getNode().layout();
                    view.seekProgress(.35);
                    for (int k = 0; k < 12; k++) view.tick(1.0 / 30);
                    WritableImage shot = scene.snapshot(null);
                    int sw = (int) shot.getWidth(), sh = (int) shot.getHeight();
                    int[] sp = new int[sw * sh];
                    shot.getPixelReader().getPixels(0, 0, sw, sh, javafx.scene.image.PixelFormat.getIntArgbInstance(), sp, 0, sw);
                    BufferedImage so = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
                    so.setRGB(0, 0, sw, sh, sp, 0, sw);
                    ImageIO.write(so, "png", output.resolve("full.png").toFile());
                    view.seekProgress(1);
                    for (int k = 0; k < 6; k++) view.tick(1.0 / 30);
                }
                // La carte de la réaction, une fois la lecture finie.
                javafx.scene.Node mapButton = view.getNode().lookup("#collision-map");
                if (mapButton instanceof javafx.scene.control.ToggleButton toggle) {
                    toggle.setSelected(true);
                    for (int k = 0; k < 20; k++) view.tick(1.0 / 30);
                    WritableImage shot = scene.snapshot(null);
                    int sw = (int) shot.getWidth(), sh = (int) shot.getHeight();
                    int[] sp = new int[sw * sh];
                    shot.getPixelReader().getPixels(0, 0, sw, sh, javafx.scene.image.PixelFormat.getIntArgbInstance(), sp, 0, sw);
                    BufferedImage so = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
                    so.setRGB(0, 0, sw, sh, sp, 0, sw);
                    ImageIO.write(so, "png", output.resolve("map.png").toFile());
                    toggle.setSelected(false);
                }
                // Les trois pages du bilan, en entier (le contenu déroulé), pour les relire.
                javafx.scene.layout.Region body = view.resultNode();
                for (int tab = 0; tab <= 2; tab++) {
                    javafx.scene.Node button = near.lookup("#collision-result-tab-" + tab);
                    if (!(button instanceof javafx.scene.control.Button b)) break;
                    b.fire();
                    body.resize(472, 1250);
                    body.applyCss();
                    body.layout();
                    javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
                    params.setFill(javafx.scene.paint.Color.web("#0A121E"));
                    WritableImage page = body.snapshot(params, null);
                    int pw = (int) page.getWidth(), ph = (int) page.getHeight();
                    int[] pagePixels = new int[pw * ph];
                    page.getPixelReader().getPixels(0, 0, pw, ph, javafx.scene.image.PixelFormat.getIntArgbInstance(), pagePixels, 0, pw);
                    BufferedImage pageOut = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
                    pageOut.setRGB(0, 0, pw, ph, pagePixels, 0, pw);
                    ImageIO.write(pageOut, "png", output.resolve("tab" + tab + ".png").toFile());
                }
                System.out.println("frames=" + count + " elapsed_s=" + (System.nanoTime() - start) / 1e9);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            } finally {
                Platform.exit();
            }
        });
    }
}
