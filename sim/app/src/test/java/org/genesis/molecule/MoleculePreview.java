package org.genesis.molecule;

import org.genesis.app.I18n;

import com.studio.genesis.grpc.UniverseFrame;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Image de la vraie vue du laboratoire moléculaire alimentée par une frame du moteur
 * (tools/export_molecule_frames.py), sans serveur ni fenêtre : frame.pb sortie.png [largeur] [hauteur] [model].
 */
public final class MoleculePreview {
    public static void main(String[] args) throws Exception {
        // Plusieurs frames séparées par « ; » : appliquées l'une après l'autre (un changement de vue, par exemple) ;
        // l'image est celle de la dernière.
        String[] files = args[0].split(";");
        UniverseFrame frame = UniverseFrame.parseFrom(Files.readAllBytes(Path.of(files[files.length - 1])));
        if (System.getProperty("genesis.lang") != null) {
            I18n.setLanguage(I18n.Language.valueOf(System.getProperty("genesis.lang").toUpperCase(java.util.Locale.ROOT)));
        }
        Platform.startup(() -> { });
        Platform.runLater(() -> {
            try {
                MoleculeView view = new MoleculeView(new MoleculeView.Actions() {
                    @Override public void back() { }
                    @Override public void preset(int index) { }
                    @Override public void reaction(int index) { }
                    @Override public void addAtom(int z, int anchor, double[] position) { }
                    @Override public void removeAtom(int index) { }
                    @Override public void drag(int index, double[] target) { }
                    @Override public void release() { }
                    @Override public void dynamics(int mode, double kelvin) { }
                    @Override public void spark(int index, double ev) { }
                    @Override public void view(int kind, int orbital, boolean potential) { }
                    @Override public void modes() { }
                    @Override public void excite(int mode, double quanta) { }
                    @Override public void charge(int charge) { }
                    @Override public void pause(boolean paused) { }
                    @Override public void isotope(int target, int massNumber) { }
                    @Override public void clear() { }
                    @Override public void pickElement() { }
                });
                int width = args.length > 2 ? Integer.parseInt(args[2]) : 1440;
                int height = args.length > 3 ? Integer.parseInt(args[3]) : 900;
                Scene scene = new Scene(view.getNode(), width, height);
                I18n.attach(scene);
                scene.getStylesheets().add(MoleculePreview.class.getResource("/genesis.css").toExternalForm());
                view.getNode().applyCss();
                view.getNode().resize(width, height);
                view.getNode().layout();
                for (int f = 0; f < files.length - 1; f++) {
                    view.applyFrame(UniverseFrame.parseFrom(Files.readAllBytes(Path.of(files[f]))));
                    for (int i = 0; i < 20; i++) view.tick(.05);
                }
                view.applyFrame(frame);
                if (args.length > 4 && args[4].equals("model")) view.showModel();
                if (args.length > 4 && args[4].equals("bonds")) {
                    view.scene.showHalo = false;
                    view.scene.showField = false;
                    view.scene.showInner = false;
                }
                if (args.length > 4 && args[4].equals("potential")) {
                    view.scene.showPotential = true;
                    view.scene.retint();
                }
                if (args.length > 4 && args[4].startsWith("select")) {
                    view.scene.selected = Integer.parseInt(args[4].substring(6));
                    view.applyFrame(frame);
                }
                // -Dgenesis.view=lacet,site : un autre point de vue que celui du cadrage automatique.
                // -Dgenesis.zoom=log10(distance) : plus ou moins de recul.
                if (System.getProperty("genesis.zoom") != null) view.scene.camera.flyTo(Double.parseDouble(System.getProperty("genesis.zoom")));
                if (System.getProperty("genesis.view") != null) {
                    String[] angles = System.getProperty("genesis.view").split(",");
                    view.scene.camera.orient(Double.parseDouble(angles[0]), Double.parseDouble(angles[1]));
                }
                // La caméra glisse vers son point de vue (quelques secondes) : on la laisse s'y poser.
                for (int i = 0; i < 160; i++) view.tick(.05);
                view.applyFrame(frame);                               // les panneaux, avec la caméra posée
                view.scene.awaitSurface(8000);                       // les lobes de verre se construisent à part
                view.tick(.05);
                if (args.length > 5) {
                    for (String flag : args[5].split(",")) {
                        if (flag.equals("noenvelope")) view.scene.showHalo = false;
                        if (flag.equals("nofield")) view.scene.showField = false;
                        if (flag.equals("nothreads")) view.scene.showBonds = false;
                        if (flag.equals("noinner")) view.scene.showInner = false;
                        if (flag.equals("foldlegend")) view.scene.legendFolded = true;
                        if (flag.equals("byterm")) view.atomMapMode(AtomMap.BY_TERM);
                        if (flag.equals("bypair")) view.atomMapMode(AtomMap.BY_PAIR);
                        if (flag.equals("allh")) view.sketchAllHydrogens();
                    }
                    view.tick(.05);                                   // une image de plus, avec ces réglages
                }
                if (args.length > 4 && args[4].equals("apply")) {
                    // Le coût d'une frame reçue, sur le fil de l'interface (décodage et mise à jour des panneaux).
                    long start = System.nanoTime();
                    int runs = Integer.getInteger("genesis.runs", 30);
                    for (int i = 0; i < runs; i++) view.applyFrame(frame);
                    System.out.printf("%.1f ms par frame reçue%n", (System.nanoTime() - start) / (runs * 1e6));
                }
                if (args.length > 4 && args[4].equals("time")) {
                    // Le coût d'une image pendant qu'on tourne la vue (le brouillard est recalculé à chaque image).
                    long start = System.nanoTime();
                    for (int i = 0; i < 30; i++) {
                        view.scene.camera.yaw += .02;
                        view.tick(1 / 60.0);
                    }
                    System.out.printf("%.1f ms par image en rotation%n", (System.nanoTime() - start) / 30e6);
                }
                write(scene.snapshot(null), args[1]);
                // -Dgenesis.readings=fichier.png : la colonne des lectures en entier (06 à 10), sans le défilement.
                if (System.getProperty("genesis.readings") != null) {
                    javafx.scene.control.ScrollPane right = (javafx.scene.control.ScrollPane)
                            ((javafx.scene.layout.BorderPane) view.getNode()).getRight();
                    javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
                    parameters.setFill(javafx.scene.paint.Color.web("#060A14"));
                    write(right.getContent().snapshot(parameters, null), System.getProperty("genesis.readings"));
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            } finally {
                Platform.exit();
            }
        });
    }

    private static void write(WritableImage image, String path) throws java.io.IOException {
        BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[out.getWidth() * out.getHeight()];
        image.getPixelReader().getPixels(0, 0, out.getWidth(), out.getHeight(), PixelFormat.getIntArgbInstance(), pixels, 0, out.getWidth());
        out.setRGB(0, 0, out.getWidth(), out.getHeight(), pixels, 0, out.getWidth());
        ImageIO.write(out, "png", Path.of(path).toFile());
    }
}
