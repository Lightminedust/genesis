package org.genesis.fusion;

import com.studio.genesis.grpc.*;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

public class ReactorViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try {Platform.startup(()->{Platform.setImplicitExit(false);ready.countDown();});}
        catch(IllegalStateException started){ready.countDown();}
        assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    private static void fx(Runnable work)throws Exception{
        FutureTask<Void> task=new FutureTask<>(work,null);Platform.runLater(task);task.get(20,TimeUnit.SECONDS);
    }
    private static ReactorView view(Runnable fire){
        var view=new ReactorView(()->{},i->{},v->{},fire);
        Scene scene=new Scene(view.getNode(),1365,768);
        scene.getStylesheets().add(ReactorViewTest.class.getResource("/genesis.css").toExternalForm());
        view.getNode().applyCss();view.getNode().resize(1365,768);view.getNode().layout();return view;
    }
    private static CollisionNucleus nucleus(int z,int a,String symbol){return CollisionNucleus.newBuilder().setZ(z).setA(a).setSymbol(symbol).setLabel(symbol+"-"+a).setName(symbol).setMassU(a).setRadiusFm(3).setChargeState(z).build();}
    private static CollisionBody body(int z,int a,double x){return CollisionBody.newBuilder().setZ(z).setA(a).setRadiusFm(3).setLabel("test").addPosition(x).addPosition(0).addPosition(0).build();}
    private static UniverseFrame frame(boolean shot){
        var state=ReactorState.newBuilder().setActive(true).setSelectedA(nucleus(6,12,"C")).setSelectedB(nucleus(8,16,"O")).setRequestedEnergyKev(20000).setSuggestedEnergyKev(18000).setFieldT(5.3);
        if(shot){var run=CollisionRun.newBuilder().setRun(1).setContactFm(6).setTimeScaleS(1e-21).setReaction("C + O -> diffusion").setOutcomeResolved(true);
            for(int i=0;i<5;i++)run.addSamples(CollisionSample.newBuilder().setTimeS((i-2)*1e-21).setPhase(i<3?"approach":"scattered").addBodies(body(6,12,-30+5*i)).addBodies(body(8,16,30-5*i)));
            state.setCollision(run);
        }else state.addReactants(ReactorParticle.newBuilder().setZ(6).setA(12)).addReactants(ReactorParticle.newBuilder().setZ(8).setA(16));
        return UniverseFrame.newBuilder().setReactor(state).build();
    }
    @Test void beamAndFireControlsSendActionsAndErrorsReleaseTheButton()throws Exception{fx(()->{
        AtomicInteger shots=new AtomicInteger();AtomicReference<Double> energy=new AtomicReference<>();
        ReactorView view=view(shots::incrementAndGet);view.setOnCollisionEnergy(energy::set);view.applyFrame(frame(false));
        TextField input=(TextField)view.getNode().lookup("#collision-energy-input");input.setText("25000");input.fireEvent(new ActionEvent());assertEquals(25000,energy.get());
        Button fire=(Button)view.getNode().lookup("#collision-fire");fire.fire();assertEquals(1,shots.get());assertTrue(fire.isDisabled());
        view.applyFrame(frame(false).toBuilder().setReactor(frame(false).getReactor().toBuilder().setError("Hors domaine")).build());assertFalse(fire.isDisabled());
    });}
    @Test void typedEnergyIsSentWithTheShotAndARefusalOffersTheFix()throws Exception{fx(()->{
        AtomicInteger forced=new AtomicInteger();java.util.List<Double> energies=new java.util.ArrayList<>();
        ReactorView view=view(()->{});view.setOnForce(forced::incrementAndGet);view.setOnCollisionEnergy(energies::add);view.applyFrame(frame(false));
        TextField input=(TextField)view.getNode().lookup("#collision-energy-input");input.setText("30000");
        ((Button)view.getNode().lookup("#collision-force")).fire();
        assertEquals(java.util.List.of(30000.0),energies,"l'énergie tapée sans Entrée doit partir avec le tir");assertEquals(1,forced.get());
        view.applyFrame(frame(false).toBuilder().setReactor(frame(false).getReactor().toBuilder().setError("Energie trop basse : le mur de Coulomb est a 9 MeV")).build());
        Button fix=(Button)view.getNode().lookup("#collision-error-fix");
        assertTrue(fix.isVisible()&&fix.getParent().getParent().isVisible(),"le refus doit s'afficher en grand, avec sa correction");
        fix.fire();assertEquals(18000.0,energies.get(energies.size()-1));assertEquals(2,forced.get());
    });}
    @Test void pauseReplayAndSeekingUseCalculatedTime()throws Exception{fx(()->{
        ReactorView view=view(()->{});view.applyFrame(frame(true));view.seekProgress(.75);view.tick(.04);double later=view.physicalTime();
        view.tick(.1);assertEquals(later,view.physicalTime());
        ((Button)view.getNode().lookup("#collision-replay")).fire();view.tick(.04);assertTrue(view.physicalTime()<later);
        double start=view.physicalTime();view.tick(.1);assertTrue(view.physicalTime()>start);
        view.seekProgress(1);view.tick(.04);assertEquals(2e-21,view.physicalTime(),1e-30);
    });}
    @Test void statusFramesKeepTheTrajectoryAndPlaybackClock()throws Exception{fx(()->{
        ReactorView view=view(()->{});view.applyFrame(frame(true));view.tick(.1);
        double before=view.physicalTime();
        // fill_collision sends samples once, then metadata with fired=false and no reactants.
        var status=frame(true).toBuilder().setReactor(frame(true).getReactor().toBuilder()
                .clearCollision().clearFired().setRun(1).setRequestedEnergyKev(25000)).build();
        view.applyFrame(status);assertEquals(before,view.physicalTime());
        view.tick(.1);assertTrue(view.physicalTime()>before,"une mise à jour des réglages ne doit pas effacer la lecture");
        view.seekProgress(.8);double paused=view.physicalTime();
        view.applyFrame(status);view.tick(.1);assertEquals(paused,view.physicalTime());
    });}
    @Test void duplicateTrajectoryDoesNotRestartAndANewRunDoes()throws Exception{fx(()->{
        ReactorView view=view(()->{});view.applyFrame(frame(true));view.seekProgress(.8);
        double before=view.physicalTime();view.applyFrame(frame(true));view.tick(.1);
        assertEquals(before,view.physicalTime(),"la retransmission du même tir ne doit pas quitter la pause");
        view.applyFrame(frame(true).toBuilder().setReactor(frame(true).getReactor().toBuilder()
                .setRun(2).setCollision(frame(true).getReactor().getCollision().toBuilder().setRun(2))).build());
        assertTrue(view.physicalTime()<before);view.tick(.1);assertTrue(view.physicalTime()>-2e-21);
    });}
    @Test void explicitReadyFrameClearsThePreviousShotEvenForTheSameIsotopes()throws Exception{fx(()->{
        ReactorView view=view(()->{});view.applyFrame(frame(true));view.seekProgress(.8);
        view.applyFrame(frame(false));assertEquals(0,view.physicalTime());
        view.tick(.1);assertEquals(0,view.physicalTime());
    });}
    @Test void modelProvenanceFollowsTheSelectedCollision()throws Exception{fx(()->{
        ReactorView view=view(()->{});
        var state=frame(true).getReactor();
        var estimated=state.toBuilder().setCollision(state.getCollision().toBuilder()
                .setModel("Capture effective Woods-Saxon / WKB ; sigma par ondes partielles Hill-Wheeler")).build();
        view.applyFrame(frame(true).toBuilder().setReactor(estimated).build());
        Label evidence=(Label)view.getNode().lookup("#collision-model-evidence");
        assertTrue(evidence.getText().contains("estimés"),"une réaction générique doit être signalée comme une estimation");
        view.applyFrame(frame(false));
        assertEquals("",evidence.getText(),"la provenance du tir précédent doit disparaître avec le tir");
        assertTrue(ReactorView.modelEvidence(CollisionRun.newBuilder().setModel("Bosch-Hale (2-400 keV)").build())
                .contains("mesures"));
    });}
    @Test void theTwoReadingsShowDifferentAmountsOfTheSameCalculation()throws Exception{fx(()->{
        ReactorView view=view(()->{});view.applyFrame(frame(true));view.getNode().applyCss();view.getNode().layout();
        ToggleButton simple=(ToggleButton)view.getNode().lookup("#collision-view-simple");
        ToggleButton full=(ToggleButton)view.getNode().lookup("#collision-view-full");
        assertTrue(simple.isSelected(),"la lecture simple est proposée d'abord");
        Node inspect=view.getNode().lookup("#collision-inspect");
        assertFalse(inspect.isVisible(),"la sonde n'encombre pas la lecture simple");
        full.fire();
        assertTrue(full.isSelected()&&!simple.isSelected());
        assertTrue(inspect.isVisible(),"la lecture complète ouvre la sonde et les calques");
        simple.fire();
        assertFalse(inspect.isVisible());
    });}

    @Test void everyStageOffersItsLawWithValues(){
        var readouts=new FusionReadouts((z,a)->z+":"+a);
        var run=frame(true).getReactor().getCollision();
        var timeline=new CollisionTimeline(run);
        var report=new FusionRenderer.Report();
        report.separation=40;report.kinetic=.04;report.potential=.02;
        for(String stage:new String[]{"approach","scattered","tunnel","compound","products","contact"}){
            report.stage=stage;
            var law=readouts.law(run,timeline,report);
            assertNotNull(law,stage+" doit avoir sa loi");
            assertFalse(law.value().isBlank(),stage+" : une loi sans valeur ne sert à rien");
            assertFalse(law.title().isBlank());
        }
    }

    @Test void selectionCardsHaveBoundedHeightAndFireIsOnScreen()throws Exception{fx(()->{
        ReactorView view=view(()->{});view.applyFrame(frame(false));view.getNode().applyCss();view.getNode().layout();
        Button a=(Button)view.getNode().lookup("#collision-select-a"),b=(Button)view.getNode().lookup("#collision-select-b");
        assertTrue(a.getHeight()<140);assertTrue(b.getHeight()<140);
        Button fire=(Button)view.getNode().lookup("#collision-fire");assertTrue(fire.localToScene(fire.getBoundsInLocal()).getMaxY()<768);
    });}
}
