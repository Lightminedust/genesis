package org.genesis.app;

import com.studio.genesis.grpc.UniverseFrame;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/**
 * L'écran de l'atome tout entier (barre du haut, scène, barre de vue,
 * timeline, onglets, journal), sans fenêtre ni réseau : des frames réelles
 * du moteur lui sont données comme si elles arrivaient. L'uranium, puis sa
 * désintégration α vers le thorium, image par image.
 */
public final class DashboardPreview {
    private static long now = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        Path frames = Path.of(args.length > 0 ? args[0] : "../../artifacts/frames");
        Path out = Path.of(args.length > 1 ? args[1] : "../../artifacts/dashboard");
        Files.createDirectories(out);
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                for (int[] size : new int[][]{{1440, 900}, {1365, 740}}) {
                    App app = new App();
                    Scene scene = app.buildScene(size[0], size[1]);
                    scene.snapshot(null);          // première mise en page : les canevas prennent leur taille
                    app.previewJournal(false);
                    app.receive(read(frames, "uranium_now"));
                    run(app, 2.5);
                    String name = "dashboard_" + size[0];
                    ImageIO.write(snap(scene), "png", out.resolve(name + ".png").toFile());
                    app.previewPanel(null);
                    run(app, 0.4);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_shells.png").toFile());
                    app.previewPanel("5f");
                    run(app, 0.2);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_shells_hint.png").toFile());
                    app.previewPanel("i");
                    run(app, 0.2);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_limits.png").toFile());
                    app.receive(read(frames, "uranium_decay"));
                    BufferedImage strip = new BufferedImage(size[0] / 2 * 4, size[1] / 2, BufferedImage.TYPE_INT_ARGB);
                    double[] steps = {0.06, 0.25, 0.6, 1.4};
                    for (int i = 0; i < steps.length; i++) {
                        run(app, steps[i]);
                        BufferedImage image = snap(scene);
                        if (i == 1) {
                            ImageIO.write(image, "png", out.resolve(name + "_decay.png").toFile());
                        }
                        strip.createGraphics().drawImage(image, i * size[0] / 2, 0, size[0] / 2, size[1] / 2, null);
                    }
                    ImageIO.write(strip, "png", out.resolve(name + "_decay_film.png").toFile());
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_journal.png").toFile());
                    // Un neutron lent sur l'uranium 235 : la fission, telle que le moteur la calcule.
                    app.previewPanel(null);
                    app.receive(read(frames, "uranium235"));
                    run(app, 0.5);
                    app.receive(read(frames, "uranium235_fission"));
                    run(app, 0.9);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_neutron.png").toFile());
                    run(app, 2.5);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_neutron_after.png").toFile());
                    // L'yttrium (4d¹) : tout le nuage, puis la valence seule et la phase de ψ (retour d'utilisateur).
                    for (String element : new String[]{"yttrium", "tungsten"}) {
                        app.previewOrbitalModes(false, false);
                        app.receive(read(frames, element));
                        run(app, 1.2);
                        ImageIO.write(snap(scene), "png", out.resolve(name + "_" + element + ".png").toFile());
                        app.previewOrbitalModes(true, true);
                        run(app, 1.2);
                        ImageIO.write(snap(scene), "png", out.resolve(name + "_" + element + "_valence.png").toFile());
                    }
                    app.previewOrbitalModes(false, false);
                    // Un proton ajouté au radon : pendant le vol, c'est encore du radon ; à l'impact, du francium.
                    app.receive(read(frames, "add_proton"));
                    run(app, 0.6);
                    app.receive(read(frames, "add_proton_after"));
                    run(app, 0.45);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_proton_flight.png").toFile());
                    run(app, 0.6);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_proton_impact.png").toFile());
                    // La vue de Bohr : l'uranium, sa valence autour du cœur [Rn], une capture d'électron, une désintégration α.
                    app.previewBohr(true);
                    app.receive(read(frames, "uranium_now"));
                    run(app, 1.5);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_bohr.png").toFile());
                    app.previewOrbitalModes(true, false);
                    run(app, 0.5);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_bohr_valence.png").toFile());
                    app.previewOrbitalModes(false, false);
                    app.receive(read(frames, "uranium_decay"));
                    run(app, 0.35);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_bohr_decay.png").toFile());
                    app.receive(read(frames, "add_electron"));
                    run(app, 1.0);
                    app.receive(read(frames, "add_electron_after"));
                    run(app, 0.6);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_bohr_capture.png").toFile());
                    run(app, 1.2);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_bohr_captured.png").toFile());
                    for (String heavy : new String[]{"oganesson", "magnesium"}) {
                        app.receive(read(frames, heavy));
                        run(app, 1.5);
                        ImageIO.write(snap(scene), "png", out.resolve(name + "_bohr_" + heavy + ".png").toFile());
                    }
                    app.previewBohr(false);
                    // Un électron sur l'argon : Ar⁻ n'existe pas, il rebondit ; la case suivante clignote en rouge.
                    app.previewPanel(null);
                    app.receive(read(frames, "argon_electron"));
                    run(app, 0.4);
                    app.receive(read(frames, "argon_electron_after"));
                    run(app, 1.0);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_argon_refused.png").toFile());
                    app.previewJournal(true);
                    run(app, 9);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_genesis.png").toFile());
                    app.previewLoader();
                    run(app, 1.2);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_loader.png").toFile());
                    app.previewUpdate();
                    run(app, 1.0);
                    ImageIO.write(snap(scene), "png", out.resolve(name + "_update.png").toFile());
                }
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    private static UniverseFrame read(Path frames, String name) throws Exception {
        return UniverseFrame.parseFrom(Files.readAllBytes(frames.resolve(name + ".bin")));
    }

    private static void run(App app, double seconds) {
        for (int k = 0; k < Math.round(seconds * 60); k++) {
            now += 16_666_667L;
            app.tickForPreview(now);
        }
    }

    private static BufferedImage snap(Scene scene) {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] px = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }
}
