package org.genesis;

import com.studio.genesis.grpc.UniverseEngineGrpc;
import com.studio.genesis.grpc.UniverseFrame;
import com.studio.genesis.grpc.UserAction;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EngineClientTest {
    @Test
    void largeCollisionFrameDoesNotDisconnectTheClient() throws Exception {
        // Taille observée sur U-238 + Au-197 à 1 GeV : plus de 4 Mio.
        UniverseFrame frame = UniverseFrame.newBuilder()
                .setProtocolVersion(EngineClient.PROTOCOL_VERSION)
                .setSource("x".repeat(4_500_000))
                .build();
        assertTrue(frame.getSerializedSize() > 4 * 1024 * 1024);

        Server server = ServerBuilder.forPort(0).addService(new UniverseEngineGrpc.UniverseEngineImplBase() {
            @Override
            public StreamObserver<UserAction> runSimulation(StreamObserver<UniverseFrame> replies) {
                return new StreamObserver<>() {
                    @Override public void onNext(UserAction action) { replies.onNext(frame); }
                    @Override public void onError(Throwable error) { }
                    @Override public void onCompleted() { replies.onCompleted(); }
                };
            }
        }).build().start();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<UniverseFrame> received = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        EngineClient client = null;
        try {
            client = new EngineClient("localhost", server.getPort(),
                    value -> { received.set(value); delivered.countDown(); },
                    error -> { failure.set(error); delivered.countDown(); }, delivered::countDown);
            client.send(UserAction.ActionType.HELLO);
            assertTrue(delivered.await(15, TimeUnit.SECONDS), "la réponse lourde n'est pas arrivée");
            assertNull(failure.get(), "le flux gRPC a été interrompu");
            assertNotNull(received.get());
            assertEquals(frame.getSerializedSize(), received.get().getSerializedSize());
        } finally {
            if (client != null) client.abandon();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
