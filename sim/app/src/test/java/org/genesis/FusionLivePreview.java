package org.genesis;

import com.studio.genesis.grpc.*;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static com.studio.genesis.grpc.UserAction.ActionType.*;

/** Vérifie le vrai flux Python -> gRPC -> AnimationTimer -> Canvas, avec des mises à jour pendant le tir. */
public final class FusionLivePreview {
    private EngineClient client;
    private ReactorView view;
    private Scene scene;
    private AnimationTimer timer;
    private CollisionRun run;
    private Path output;
    private int a, b, statusFrames, captures, ticks;
    private double energy, elapsed, captureAt, updateAt = .6, previousTime;
    private boolean configured, forced, finished;
    private long previous, started = System.nanoTime();
    private long paintNanos;
    private final List<String> metrics = new ArrayList<>(List.of("screen_s,physical_s,phase,separation_fm,relative_beta,coulomb_N,status_frames"));

    public static void main(String[] args) {
        Platform.startup(() -> new FusionLivePreview().start(args));
    }

    private void start(String[] args) {
        try {
            Platform.setImplicitExit(false);
            output = Path.of(args[0]); Files.createDirectories(output);
            a = Integer.parseInt(args[1]); b = Integer.parseInt(args[2]); energy = Double.parseDouble(args[3]);
            forced = Boolean.parseBoolean(args[4]);
            view = new ReactorView(() -> {}, i -> {}, v -> {}, () -> {});
            scene = new Scene(view.getNode(), 1365, 768);
            scene.getStylesheets().add(getClass().getResource("/genesis.css").toExternalForm());
            view.getNode().applyCss(); view.getNode().resize(1365,768); view.getNode().layout();
            // Garder le bilan disponible sans ouvrir une fenêtre pendant l'enregistrement.
            new Scene(view.resultNode(),472,568);
            client = new EngineClient("localhost", Integer.parseInt(args[5]),
                    frame -> Platform.runLater(() -> receive(frame)),
                    error -> Platform.runLater(() -> finish(error)),
                    () -> Platform.runLater(() -> { if (!finished) finish(new IllegalStateException("Flux interrompu")); }));
            client.send(REACTOR_START);
            timer = new AnimationTimer() {
                @Override public void handle(long now) {
                    try { pulse(now); } catch (Throwable error) { finish(error); }
                }
            };
            timer.start();
        } catch (Throwable error) { finish(error); }
    }

    private void receive(UniverseFrame frame) {
        if (finished) return;
        try {
            if (frame.getProtocolVersion() != EngineClient.PROTOCOL_VERSION) throw new IllegalStateException("Protocole incorrect");
            ReactorState state = frame.getReactor();
            if (!state.getActive()) throw new IllegalStateException("Retour inattendu à l'atome");
            if (!state.getError().isBlank()) throw new IllegalStateException(state.getError());
            double timeBefore = view.physicalTime();
            view.applyFrame(frame);
            if (!configured) {
                configured = true;
                client.send(SET_REACTOR_NUCLEUS_A,a); client.send(SET_REACTOR_NUCLEUS_B,b);
                client.send(SET_REACTOR_ENERGY,energy); client.send(REACTOR_FIRE,forced ? 1 : 0);
            }
            if (state.hasCollision()) {
                run = state.getCollision(); previousTime = view.physicalTime();
                Files.write(output.resolve("received.pb"),frame.toByteArray());
            } else if (run != null && state.getRun() == run.getRun() && state.getReactantsCount() == 0) {
                statusFrames++;
                if (view.physicalTime() != timeBefore) throw new IllegalStateException("La mise à jour a changé le temps de lecture");
            }
        } catch (Throwable error) { finish(error); }
    }

    private void pulse(long now) throws Exception {
        if (finished) return;
        if ((now-started)/1e9>120) throw new IllegalStateException("Délai de vérification dépassé");
        double dt = previous == 0 ? 0 : Math.min(.1,(now-previous)/1e9); previous = now;
        if (run == null) return;
        elapsed += dt; ticks++;
        long painting = System.nanoTime();
        view.tick(dt);
        paintNanos += System.nanoTime() - painting;
        double time = view.physicalTime();
        if (time < previousTime) throw new IllegalStateException("La lecture est repartie en arrière");
        previousTime = time;
        if (elapsed >= updateAt) {
            client.send(SET_REACTOR_ENERGY,energy); updateAt += .6;
        }
        if (elapsed >= captureAt) {
            var r = view.playbackReport();
            if (r.stage.equals("ready")) throw new IllegalStateException("Le calcul affiché a été effacé");
            metrics.add(String.format(Locale.ROOT,"%.6f,%.12e,%s,%.9f,%.9f,%.9f,%d",
                    elapsed,time,r.stage,r.separation,r.speed,r.coulomb,statusFrames));
            WritableImage image = scene.snapshot(null);
            int w=(int)image.getWidth(),h=(int)image.getHeight();
            int[] pixels=new int[w*h]; image.getPixelReader().getPixels(0,0,w,h,PixelFormat.getIntArgbInstance(),pixels,0,w);
            BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);out.setRGB(0,0,w,h,pixels,0,w);
            ImageIO.write(out,"png",output.resolve(String.format("%04d.png",captures++)).toFile());
            captureAt += .2;
        }
        if (elapsed >= view.playbackDuration()+.3) {
            if (statusFrames < 2) throw new IllegalStateException("Aucune mise à jour en cours de lecture vérifiée");
            Files.write(output.resolve("playback.csv"),metrics);
            System.out.printf(Locale.ROOT,"LIVE OK pair=%d+%d samples=%d outcome=%s frames=%d ticks=%d status_frames=%d wall_s=%.2f paint_ms=%.2f%n",
                    a,b,run.getSamplesCount(),run.getOutcome(),captures,ticks,statusFrames,(now-started)/1e9,paintNanos/1e6/ticks);
            finish(null);
        }
    }

    private void finish(Throwable error) {
        if (finished) return;
        finished = true;
        if (timer != null) timer.stop();
        if (client != null) client.abandon();
        if (error != null) { error.printStackTrace(); System.exit(1); }
        Platform.exit();
    }
}
