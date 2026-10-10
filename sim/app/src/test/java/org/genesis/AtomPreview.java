package org.genesis;

import com.studio.genesis.grpc.SimEvent;
import com.studio.genesis.grpc.UniverseFrame;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javax.imageio.ImageIO;

/**
 * Aperçu de l'atome en 3D à partir de frames réelles du moteur (fichiers .bin) :
 * les nuages de plusieurs éléments, plusieurs orientations, la plongée vers le
 * noyau, la goutte nucléaire et ses pions, le gouffre des échelles.
 */
public final class AtomPreview {
    private static final int W = 1280;
    private static final int H = 800;
    private static long now = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        Path frames = Path.of(args.length > 0 ? args[0] : "../../artifacts/frames");
        Path out = Path.of(args.length > 1 ? args[1] : "../../artifacts/atom3d");
        String only = args.length > 2 ? args[2] : "";
        Files.createDirectories(out);
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                if (only.equals("photon")) {
                    photons(frames, out);
                } else {
                    run(frames, out);
                    photons(frames, out);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    private static SimCanvas open(Path frames, String name) throws Exception {
        return open(frames, name, W, H);
    }

    private static SimCanvas open(Path frames, String name, int w, int h) throws Exception {
        SimCanvas sim = new SimCanvas();
        sim.getNode().setWidth(w);
        sim.getNode().setHeight(h);
        Pane pane = new Pane(sim.getNode(), sim.getOverlay());
        sim.getOverlay().setPrefSize(w, h);
        new Scene(pane, w, h);
        sim.applyFrame(UniverseFrame.parseFrom(Files.readAllBytes(frames.resolve(name + ".bin"))));
        run(sim, 2.0);
        return sim;
    }

    /** Une frame du moteur, rejouée avec un événement (comme après une action). */
    private static UniverseFrame with(Path frames, String name, SimEvent event) throws Exception {
        return UniverseFrame.parseFrom(Files.readAllBytes(frames.resolve(name + ".bin"))).toBuilder()
                .setLastAction("FIRE_PHOTON").setStructureChanged(true).addEvents(event).build();
    }

    /**
     * Le photon : Lyman α absorbé par l'hydrogène (1s → 2p), puis 98,7 nm qui
     * arrache l'électron 3s du sodium (photoionisation, l'ion se contracte).
     * Et l'écran étroit d'une fenêtre de 1 365 px, panneau replié.
     */
    private static void photons(Path frames, Path out) throws Exception {
        String[][] stories = {{"hydrogen", "hydrogen2p", "121.567", "absorbed", "0"},
                {"sodium", "sodium_ion", "98.7", "photo_ionized", "7.42"}};
        for (String[] story : stories) {
            SimCanvas sim = open(frames, story[0]);
            sim.orient(35, -24, true);
            double nm = Double.parseDouble(story[2]);
            sim.applyFrame(with(frames, story[0], SimEvent.newBuilder().setKind("photon_fired")
                    .setWavelengthNm((float) nm).setTitle("Photon tiré").build()));
            BufferedImage film = new BufferedImage(W / 2 * 4, H / 2 * 2, BufferedImage.TYPE_INT_ARGB);
            double[] before = {0.6, 0.7, 0.6};
            int index = 0;
            for (double step : before) {
                run(sim, step);
                if (index == 2) {
                    ImageIO.write(snap(sim), "png", out.resolve(story[0] + "_flight.png").toFile());
                }
                film.createGraphics().drawImage(snap(sim), (index % 4) * W / 2, (index / 4) * H / 2, W / 2, H / 2, null);
                index++;
            }
            run(sim, 0.12);
            sim.applyFrame(with(frames, story[1], SimEvent.newBuilder().setKind(story[3])
                    .setWavelengthNm((float) nm).setKineticEv(Double.parseDouble(story[4]))
                    .setTitle(story[3].equals("absorbed") ? "Absorption Lyman α" : "Effet photoélectrique").build()));
            double[] after = {0.05, 0.18, 0.35, 0.6, 1.0};
            for (double step : after) {
                run(sim, step);
                BufferedImage image = snap(sim);
                if (index == 4 || index == 6) {
                    ImageIO.write(image, "png", out.resolve(story[0] + (index == 4 ? "_impact.png" : "_after.png")).toFile());
                }
                film.createGraphics().drawImage(image, (index % 4) * W / 2, (index / 4) * H / 2, W / 2, H / 2, null);
                index++;
            }
            ImageIO.write(film, "png", out.resolve(story[0] + "_photon.png").toFile());
        }
        // L'écran étroit de la capture : panneau replié par défaut, étiquettes dans la scène.
        SimCanvas narrow = open(frames, "uranium", 965, 560);
        narrow.orient(35, -24, true);
        narrow.applyFrame(with(frames, "uranium", SimEvent.newBuilder().setKind("photon_fired")
                .setWavelengthNm(98.7f).setTitle("Photon tiré").build()));
        run(narrow, 1.1);
        ImageIO.write(snap(narrow), "png", out.resolve("uranium_narrow.png").toFile());
    }

    private static void run(SimCanvas sim, double seconds) {
        for (int k = 0; k < Math.round(seconds * 60); k++) {
            now += 16_666_667L;
            sim.tick(now);
        }
    }

    private static double timing(SimCanvas sim) {
        long start = System.nanoTime();
        run(sim, 0.5);
        return (System.nanoTime() - start) / 30 / 1e6;
    }

    private static void run(Path frames, Path out) throws Exception {
        String[] names = {"boron", "carbon", "nitrogen", "oxygen", "neon", "scandium", "iron", "praseodymium",
                "uranium", "hydrogen", "hydrogen3d", "sodium_excited"};
        BufferedImage sheet = new BufferedImage(W / 2 * 4, H / 2 * 3, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < names.length; i++) {
            SimCanvas sim = open(frames, names[i]);
            sim.orient(35, -24, true);
            run(sim, 0.1);
            System.out.printf("%-15s %.1f ms/image%n", names[i], timing(sim));
            BufferedImage image = snap(sim);
            ImageIO.write(image, "png", out.resolve(names[i] + ".png").toFile());
            sheet.createGraphics().drawImage(image, (i % 4) * W / 2, (i / 4) * H / 2, W / 2, H / 2, null);
        }
        ImageIO.write(sheet, "png", out.resolve("elements.png").toFile());

        // Le bore (2p¹ : un sablier p_z) et le scandium (3d¹ : d_z²) sous quatre angles : la forme tourne en 3D.
        BufferedImage turns = new BufferedImage(W / 2 * 4, H / 2 * 2, BufferedImage.TYPE_INT_ARGB);
        double[][] angles = {{0, 0}, {45, -20}, {90, -45}, {20, -80}};
        String[] shaped = {"boron", "scandium"};
        for (int row = 0; row < shaped.length; row++) {
            SimCanvas turning = open(frames, shaped[row]);
            for (int i = 0; i < angles.length; i++) {
                turning.orient(angles[i][0], angles[i][1], true);
                run(turning, 0.05);
                turns.createGraphics().drawImage(snap(turning), i * W / 2, row * H / 2, W / 2, H / 2, null);
            }
        }
        ImageIO.write(turns, "png", out.resolve("rotation.png").toFile());

        // La plongée vers le noyau : 6 instants.
        SimCanvas dive = open(frames, "carbon");
        dive.orient(35, -24, true);
        dive.jumpTo(SimCanvas.Scale.NOYAU);
        BufferedImage film = new BufferedImage(W / 2 * 3, H / 2 * 2, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 6; i++) {
            run(dive, i == 0 ? 0.3 : 0.7);
            film.createGraphics().drawImage(snap(dive), (i % 3) * W / 2, (i / 3) * H / 2, W / 2, H / 2, null);
        }
        ImageIO.write(film, "png", out.resolve("dive.png").toFile());

        // Le noyau du fer et de l'uranium : la goutte, ses vibrations, ses pions.
        for (String name : new String[]{"iron", "uranium", "carbon"}) {
            SimCanvas nucleus = open(frames, name);
            nucleus.orient(35, -24, false);
            nucleus.jumpTo(SimCanvas.Scale.NOYAU);
            run(nucleus, 6);
            BufferedImage strip = new BufferedImage(W / 2 * 3, H / 2, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < 3; i++) {
                run(nucleus, 0.9);
                BufferedImage image = snap(nucleus);
                if (i == 0) {
                    ImageIO.write(image, "png", out.resolve(name + "_nucleus.png").toFile());
                }
                strip.createGraphics().drawImage(image, i * W / 2, 0, W / 2, H / 2, null);
            }
            System.out.printf("noyau %-8s %.1f ms/image%n", name, timing(nucleus));
            ImageIO.write(strip, "png", out.resolve(name + "_drop.png").toFile());
        }

        // Le gouffre des échelles.
        for (String name : new String[]{"carbon", "iron"}) {
            SimCanvas gulf = open(frames, name);
            gulf.orient(35, -24, true);
            gulf.toggleLogView();
            run(gulf, 1.5);
            System.out.printf("gouffre %-6s %.1f ms/image%n", name, timing(gulf));
            ImageIO.write(snap(gulf), "png", out.resolve(name + "_gulf.png").toFile());
        }
    }

    private static BufferedImage snap(SimCanvas sim) {
        WritableImage image = sim.getNode().getParent().snapshot(null, null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int[] px = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }
}
