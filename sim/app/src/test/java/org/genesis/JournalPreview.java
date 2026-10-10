package org.genesis;

import com.studio.genesis.grpc.SimEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javax.imageio.ImageIO;

/** Aperçu du journal de l'écran principal, avec Genesis qui explique un événement. */
public final class JournalPreview {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                EventJournal journal = new EventJournal();
                GenesisMascot genesis = new GenesisMascot(124, 152);
                genesis.setManual(true);
                Label title = new Label("JOURNAL · ce qui est arrivé, et pourquoi");
                title.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px; -fx-font-weight: bold;");
                title.setPadding(new Insets(8, 12, 4, 12));
                HBox guide = new HBox(4, genesis.getNode(), genesis.speech());
                guide.setAlignment(Pos.CENTER_LEFT);
                guide.setPadding(new Insets(0, 10, 6, 4));
                HBox.setHgrow(genesis.speech(), Priority.ALWAYS);
                VBox box = new VBox(title, guide, journal.getNode());
                VBox.setVgrow(journal.getNode(), Priority.ALWAYS);
                box.setStyle("-fx-background-color: #0B1120;");
                Scene scene = new Scene(box, 380, 420);
                for (int k = 0; k < 400; k++) genesis.tick(1 / 30.0);
                SimEvent e = SimEvent.newBuilder().setKind("emitted").setTimeS(2.3e-8).setTitle("L'électron retombe de n = 3 à n = 2")
                        .setFormula("λ = h·c / ΔE = 1239,84 / 1,889 = 656 nm").setDetail("Raie Hα de la série de Balmer.")
                        .setWavelengthNm(656.3f).setEnergyEv(1.889f).build();
                journal.add(e);
                genesis.explain(e);
                for (int k = 0; k < 300; k++) genesis.tick(1 / 30.0);
                box.applyCss();
                box.layout();
                WritableImage image = scene.snapshot(null);
                int w = (int) image.getWidth(), h = (int) image.getHeight();
                int[] px = new int[w * h];
                image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, w, h, px, 0, w);
                ImageIO.write(img, "png", out.toFile());
            } catch (Throwable ex) {
                ex.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }
}
