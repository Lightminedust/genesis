package org.genesis.chain;

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
 * Image de la vraie vue du laboratoire alimentée par une frame du moteur (tools/export_chain_frames.py), sans
 * serveur ni fenêtre : frame.pb sortie.png [progression 0–2] [largeur] [hauteur] [full].
 */
public final class ChainPreview {
    public static void main(String[] args) throws Exception {
        UniverseFrame frame = UniverseFrame.parseFrom(Files.readAllBytes(Path.of(args[0])));
        if (System.getProperty("genesis.lang") != null) {
            I18n.setLanguage(I18n.Language.valueOf(System.getProperty("genesis.lang").toUpperCase(java.util.Locale.ROOT)));
        }
        Platform.startup(() -> { });
        Platform.runLater(() -> {
            try {
                ChainView view = new ChainView(new ChainView.Actions() {
                    @Override public void back() { }
                    @Override public void core(int index) { }
                    @Override public void radius(double cm) { }
                    @Override public void reflector(int index, double cm) { }
                    @Override public void compression(double factor) { }
                    @Override public void geometry(double cm, double compression) { }
                    @Override public void fire(double[] position, int count) { }
                    @Override public void mode(int mode) { }
                    @Override public void nuclide(int code) { }
                    @Override public void critical() { }
                    @Override public void pickElement() { }
                });
                String[] symbols = new String[120];
                String[] list = ("H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr "
                        + "Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu "
                        + "Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am Cm Bk Cf Es Fm Md No Lr Rf Db Sg "
                        + "Bh Hs Mt Ds Rg Cn Nh Fl Mc Lv Ts Og").split(" ");
                for (int i = 0; i < list.length; i++) symbols[i + 1] = list[i];
                view.setSymbols(symbols);
                int width = args.length > 3 ? Integer.parseInt(args[3]) : 1440;
                int height = args.length > 4 ? Integer.parseInt(args[4]) : 900;
                Scene scene = new Scene(view.getNode(), width, height);
                I18n.attach(scene);
                scene.getStylesheets().add(ChainPreview.class.getResource("/genesis.css").toExternalForm());
                view.getNode().applyCss();
                view.getNode().resize(width, height);
                view.getNode().layout();
                view.applyFrame(frame);
                if (args.length > 5 && args[5].equals("full")) view.showFull();
                double progress = args.length > 2 ? Double.parseDouble(args[2]) : 0;
                for (int i = 0; i < 6; i++) {
                    view.seek(progress);
                    view.tick(.05);
                }
                WritableImage image = scene.snapshot(null);
                view.seek(progress);
                view.tick(.05);
                image = scene.snapshot(null);
                BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[out.getWidth() * out.getHeight()];
                image.getPixelReader().getPixels(0, 0, out.getWidth(), out.getHeight(), PixelFormat.getIntArgbInstance(), pixels, 0, out.getWidth());
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
