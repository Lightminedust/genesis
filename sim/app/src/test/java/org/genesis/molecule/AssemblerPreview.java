package org.genesis.molecule;

import com.studio.genesis.grpc.MoleculeEntry;
import com.studio.genesis.grpc.UniverseFrame;
import org.genesis.app.I18n;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Aperçu de l'assembleur, sans serveur. Deux passes :
 * <ol>
 *   <li>{@code AssemblerPreview catalog.pb out.png "eau,éthanol" drawing.txt} : pose ces modèles du dictionnaire (la vraie
 *       mise en page), écrit le dessin (le vecteur envoyé au moteur) et l'image sans verdict ;</li>
 *   <li>{@code AssemblerPreview verdict.pb out.png - drawing.txt} : recharge ce dessin (demande n° 1) et la frame où le
 *       moteur l'a jugé, et rend l'écran avec le verdict.</li>
 * </ol>
 */
public final class AssemblerPreview {
    public static void main(String[] args) throws Exception {
        if (System.getProperty("genesis.lang") != null) {
            I18n.setLanguage(I18n.Language.valueOf(System.getProperty("genesis.lang").toUpperCase(java.util.Locale.ROOT)));
        }
        UniverseFrame frame = UniverseFrame.parseFrom(Files.readAllBytes(Path.of(args[0])));
        Platform.startup(() -> {
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
                Scene scene = new Scene(view.getNode(), 1440, 900);
                I18n.attach(scene);
                scene.getStylesheets().add(AssemblerPreview.class.getResource("/genesis.css").toExternalForm());
                view.getNode().applyCss();
                view.getNode().resize(1440, 900);
                view.getNode().layout();
                view.applyFrame(frame);
                view.showAssembler(true);
                view.getNode().layout();
                MoleculeAssembler assembler = view.assembler();
                Path drawing = Path.of(args[3]);
                if (!args[2].equals("-")) {
                    List<MoleculeEntry> entries = frame.getMoleculeLab().getDictionaryList();
                    for (String name : args[2].split(",")) {
                        for (MoleculeEntry e : entries) {
                            if (e.getName().equals(name)) {
                                assembler.insert(e);
                                break;
                            }
                        }
                    }
                    if (Boolean.getBoolean("genesis.strip")) {
                        // Un dessin qu'on n'a pas fini : les H du dernier modèle retirés (valences libres).
                        for (int k = assembler.atoms.size() - 1; k >= 0 && assembler.atoms.get(k).z == 1; k--) {
                            final int index = k;
                            assembler.links.removeIf(l -> l.a == index || l.b == index);
                            assembler.atoms.remove(k);
                        }
                    }
                    double[] v = assembler.vector();
                    StringBuilder b = new StringBuilder();
                    for (double x : v) b.append(x).append('\n');
                    Files.writeString(drawing, b.toString());
                    assembler.load(v, 1);
                } else {
                    List<Double> values = new ArrayList<>();
                    for (String line : Files.readAllLines(drawing)) if (!line.isBlank()) values.add(Double.parseDouble(line));
                    double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
                    assembler.load(v, 1);
                    view.applyFrame(frame);
                }
                // -Dgenesis.gesture=outil;atomes choisis;atome survolé;dx,dy : un geste figé (outil 0 dessiner, 1 sélection…),
                // la souris à (dx, dy) px de l'atome survolé (ou du coin haut gauche sans atome).
                if (System.getProperty("genesis.gesture") != null) {
                    String[] parts = System.getProperty("genesis.gesture").split(";", -1);
                    List<Integer> chosen = new ArrayList<>();
                    for (String s : parts[1].split(",")) if (!s.isBlank()) chosen.add(Integer.parseInt(s.trim()));
                    int hover = Integer.parseInt(parts[2]);
                    String[] d = parts[3].split(",");
                    double[] at = hover >= 0 ? assembler.screen(hover) : new double[]{0, 0};
                    assembler.preview(Integer.parseInt(parts[0]), chosen, hover, at[0] + Double.parseDouble(d[0]),
                            at[1] + Double.parseDouble(d[1]));
                    view.getNode().layout();
                    assembler.draw();
                }
                assembler.draw();
                WritableImage image = scene.snapshot(null);
                BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[out.getWidth() * out.getHeight()];
                image.getPixelReader().getPixels(0, 0, out.getWidth(), out.getHeight(), PixelFormat.getIntArgbInstance(), pixels, 0,
                        out.getWidth());
                out.setRGB(0, 0, out.getWidth(), out.getHeight(), pixels, 0, out.getWidth());
                ImageIO.write(out, "png", Path.of(args[1]).toFile());
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            } finally {
                Platform.exit();
            }
        });
    }
}
