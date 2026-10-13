package org.genesis.subatomic;

import com.studio.genesis.grpc.SubatomicParticle;
import com.studio.genesis.grpc.SubatomicPair;
import com.studio.genesis.grpc.SubatomicSample;
import com.studio.genesis.grpc.SubatomicState;
import com.studio.genesis.grpc.UniverseFrame;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class SubatomicViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); }); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void separateScreenConsumesComputedSamplesAtBothScales() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            var view = new SubatomicView(() -> {}, () -> {}, p -> {}, n -> {}, e -> {}, energy -> {}, kick -> {});
            new Scene(view.getNode(), 1100, 760);
            view.getNode().applyCss();
            view.getNode().layout();
            var pair = SubatomicSample.newBuilder().setTimeFmc(0).setKineticMev(1)
                    .setStrongMev(-3).setTotalMev(-2)
                    .setBindingMarginMev(2).setLocallyBoundCount(2)
                    .addPairs(SubatomicPair.newBuilder().setFirst(0).setSecond(1)
                            .setSeparationFm(2).setEffectiveForceMevFm(-5)
                            .setEffectivePotentialMev(-3))
                    .addParticles(SubatomicParticle.newBuilder().setKind(SubatomicParticle.Kind.PROTON)
                            .addPositionFm(-1).addPositionFm(0).addPositionFm(0)
                            .addVelocityC(.02).addVelocityC(0).addVelocityC(0))
                    .addParticles(SubatomicParticle.newBuilder().setKind(SubatomicParticle.Kind.NEUTRON)
                            .addPositionFm(1).addPositionFm(0).addPositionFm(0)
                            .addVelocityC(-.02).addVelocityC(0).addVelocityC(0));
            var state = SubatomicState.newBuilder().setActive(true).setProtons(1).setNeutrons(1)
                    .setElectrons(1).setElectronRadiusPm(52.9177).setStatus("paire liée")
                    .setNote("modèle effectif").addSamples(pair).build();
            view.applyFrame(UniverseFrame.newBuilder().setSubatomic(state).build());
            view.tick(.016);
            Canvas canvas = (Canvas) ((StackPane) view.getNode().getCenter()).getChildren().get(0);
            assertTrue(canvas.getWidth() > 100);
            assertTrue(canvas.getHeight() > 100);
            assertEquals(1, view.protons());
            assertEquals(1, view.neutrons());
            assertEquals(1, view.electrons());
            HBox controls = (HBox) view.getNode().getBottom();
            ((ToggleButton) controls.getChildren().get(3)).setSelected(true);
            view.tick(.016);
            view.togglePlayback();
            return null;
        });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
    }
}
