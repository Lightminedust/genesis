package org.genesis;

import com.studio.genesis.grpc.CollisionBody;
import com.studio.genesis.grpc.CollisionRun;
import com.studio.genesis.grpc.CollisionSample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FusionPlaybackTest {
    private CollisionSample sample(double time, String phase, int z, int a, double x) {
        return CollisionSample.newBuilder().setTimeS(time).setPhase(phase)
                .addBodies(CollisionBody.newBuilder().setZ(z).setA(a)
                        .addPosition(x).addPosition(0).addPosition(0)).build();
    }

    @Test void interpolatesPositionsUsingPhysicalTime() {
        var run = CollisionRun.newBuilder()
                .addSamples(sample(-4, "approach", 1, 2, 10))
                .addSamples(sample(-2, "approach", 1, 2, 4)).build();
        var state = FusionRenderer.snapshot(run, -3);
        assertEquals(7, state.bodies().get(0).position().x(), 1e-12);
    }

    @Test void renderedMotionUsesTheEngineVelocitiesBetweenSamples() {
        // Freinage constant : 20 Mm/s -> repos, 10 fm parcourus en 10^-21 s.
        var first = sample(0, "approach", 3, 7, 0).toBuilder();
        first.setBodies(0, first.getBodies(0).toBuilder().addVelocity(2e7).addVelocity(0).addVelocity(0));
        var last = sample(1e-21, "approach", 3, 7, 10).toBuilder();
        last.setBodies(0, last.getBodies(0).toBuilder().addVelocity(0).addVelocity(0).addVelocity(0));
        var run = CollisionRun.newBuilder().addSamples(first).addSamples(last).build();
        var middle = FusionRenderer.snapshot(run, .5e-21).bodies().get(0);
        assertEquals(7.5, middle.position().x(), 1e-12);
        assertEquals(1e7, middle.velocity().x(), 1e-6);
        double h = 1e-26;
        double derivative = (FusionRenderer.snapshot(run, .5e-21+h).bodies().get(0).position().x()
                - FusionRenderer.snapshot(run, .5e-21-h).bodies().get(0).position().x()) / (2*h) * 1e-15;
        assertEquals(middle.velocity().x(), derivative, .01);
        assertEquals(0, FusionRenderer.snapshot(run, 1e-21).bodies().get(0).velocity().norm());
    }

    @Test void reactionChangesIdentityWithoutInventingATunnelTrajectory() {
        var run = CollisionRun.newBuilder()
                .addSamples(sample(-1, "approach", 1, 2, 10))
                .addSamples(sample(0, "products", 2, 4, 0)).build();
        var before = FusionRenderer.snapshot(run, -.5);
        assertEquals("approach", before.phase());
        assertEquals(2, before.bodies().get(0).a());
        assertEquals(10, before.bodies().get(0).position().x());
        var after = FusionRenderer.snapshot(run, 0);
        assertEquals("products", after.phase());
        assertEquals(4, after.bodies().get(0).a());
        assertEquals(0, after.bodies().get(0).position().x());
    }

    @Test void scissionAppliesTheOutgoingVelocitiesAtTheExactEngineTime() {
        double birth = 2e-21;
        var incoming = CollisionSample.newBuilder().setTimeS(Math.nextDown(birth)).setPhase("contact");
        var outgoing = CollisionSample.newBuilder().setTimeS(birth).setPhase("evaporation");
        for (int sign : new int[]{-1, 1}) {
            incoming.addBodies(CollisionBody.newBuilder().setZ(sign < 0 ? 92 : 79).setA(sign < 0 ? 238 : 197)
                    .addPosition(sign * 8).addPosition(0).addPosition(0).addVelocity(0).addVelocity(0).addVelocity(0));
            outgoing.addBodies(CollisionBody.newBuilder().setZ(sign < 0 ? 91 : 80).setA(sign < 0 ? 235 : 200)
                    .addPosition(sign * 8).addPosition(0).addPosition(0).addVelocity(sign * 1.5e7).addVelocity(0).addVelocity(0));
        }
        var run = CollisionRun.newBuilder().addSamples(incoming).addSamples(outgoing).build();
        assertEquals(0, FusionRenderer.snapshot(run, Math.nextDown(birth)).bodies().get(0).velocity().norm());
        var atBirth = FusionRenderer.snapshot(run, birth);
        assertEquals("evaporation", atBirth.phase());
        assertEquals(235, atBirth.bodies().get(0).a());
        assertEquals(-1.5e7, atBirth.bodies().get(0).velocity().x());
        assertEquals(1.5e7, atBirth.bodies().get(1).velocity().x());
    }
}
