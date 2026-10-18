package org.genesis.fusion;
import org.genesis.app.I18n;
import com.studio.genesis.grpc.UniverseFrame;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** Image de la vraie vue JavaFX alimentée par un calcul Python, sans serveur ni fenêtre. */
public final class FusionPreview {
    public static void main(String[] args)throws Exception {
        UniverseFrame frame=UniverseFrame.parseFrom(Files.readAllBytes(Path.of(args[0])));
        Platform.startup(()->{});
        Platform.runLater(()->{
            try {
                ReactorView view=new ReactorView(()->{},i->{},k->{},()->{});
                int width=args.length>3?Integer.parseInt(args[3]):1440;
                int height=args.length>4?Integer.parseInt(args[4]):1000;
                Scene scene=new Scene(view.getNode(),width,height);
                I18n.attach(scene);
                scene.getStylesheets().add(FusionPreview.class.getResource("/genesis.css").toExternalForm());
                view.getNode().applyCss();view.getNode().resize(width,height);view.getNode().layout();
                view.applyFrame(frame);
                if(args.length>2)view.seekTime(Double.parseDouble(args[2]));
                for(int i=0;i<8;i++){
                    if(frame.getReactor().hasCollision())view.seekTime(Double.parseDouble(args[2]));
                    else view.applyFrame(frame);
                    view.tick(.05);
                }
                if(frame.getReactor().hasCollision())view.seekTime(Double.parseDouble(args[2]));else view.applyFrame(frame);
                if(args.length>5 && args[5].equals("inspect"))
                    ((javafx.scene.control.ToggleButton)view.getNode().lookup("#collision-inspect")).fire();
                long start=System.nanoTime();view.tick(.05);
                System.out.println("render_ms="+(System.nanoTime()-start)/1e6);
                WritableImage image=scene.snapshot(null);
                BufferedImage out=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                int[] pixels=new int[out.getWidth()*out.getHeight()];
                image.getPixelReader().getPixels(0,0,out.getWidth(),out.getHeight(),javafx.scene.image.PixelFormat.getIntArgbInstance(),pixels,0,out.getWidth());
                out.setRGB(0,0,out.getWidth(),out.getHeight(),pixels,0,out.getWidth());
                ImageIO.write(out,"png",Path.of(args[1]).toFile());
            } catch(Exception e){e.printStackTrace();System.exit(1);} finally {Platform.exit();}
        });
    }
}
