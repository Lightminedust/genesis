package org.genesis.engine;

import com.studio.genesis.grpc.PeriodicTable;
import com.studio.genesis.grpc.TableRequest;
import com.studio.genesis.grpc.UniverseEngineGrpc;
import com.studio.genesis.grpc.UniverseFrame;
import com.studio.genesis.grpc.UserAction;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Liaison gRPC bidirectionnelle avec le moteur Python.
 *
 * Isole entièrement le réseau du reste de l'application : l'UI ne manipule
 * que des actions de haut niveau et reçoit des frames déjà évaluées.
 */
public class EngineClient {

    /**
     * Version du contrat attendue. Doit correspondre à PROTOCOL_VERSION dans
     * engine.py. Une frame qui ne la porte pas vient d'un moteur périmé : les
     * champs se décalent et l'affichage devient silencieusement faux, donc on
     * préfère refuser de dessiner.
     */
    public static final int PROTOCOL_VERSION = 28;
    // Une collision de noyaux lourds transporte tous ses produits et échantillons.
    // Le plafond gRPC implicite (4 Mio) coupait le flux et recréait une chambre vide.
    static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

    private final ManagedChannel channel;
    private final StreamObserver<UserAction> actions;

    /**
     * Les callbacks sont invoqués depuis un thread réseau : c'est à l'appelant
     * de basculer sur le thread JavaFX.
     */
    public EngineClient(String host, int port,
                        Consumer<UniverseFrame> onFrame,
                        Consumer<Throwable> onError,
                        Runnable onCompleted) {

        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(MAX_FRAME_BYTES)
                .build();

        UniverseEngineGrpc.UniverseEngineStub stub = UniverseEngineGrpc.newStub(channel);

        actions = stub.runSimulation(new StreamObserver<UniverseFrame>() {
            @Override
            public void onNext(UniverseFrame frame) {
                onFrame.accept(frame);
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }

            @Override
            public void onCompleted() {
                onCompleted.run();
            }
        });
    }

    public void send(UserAction.ActionType type) {
        send(UserAction.newBuilder().setType(type).build());
    }

    public void send(UserAction.ActionType type, int value) {
        send(UserAction.newBuilder().setType(type).setValue(value).build());
    }

    /** Un photon de longueur d'onde donnée (nm) part vers l'atome. */
    public void firePhoton(double nanometres) {
        send(UserAction.newBuilder().setType(UserAction.ActionType.FIRE_PHOTON).setAmount(nanometres).build());
    }

    public void send(UserAction.ActionType type, double amount) {
        send(UserAction.newBuilder().setType(type).setAmount(amount).build());
    }

    public void send(UserAction.ActionType type, int value, double amount) {
        send(UserAction.newBuilder().setType(type).setValue(value).setAmount(amount).build());
    }

    /** Un tir dans le laboratoire de la réaction en chaîne : `count` neutrons nés en `position` (cm), ou au centre. */
    public void fireChain(int count, double[] position) {
        UserAction.Builder b = UserAction.newBuilder().setType(UserAction.ActionType.CHAIN_FIRE).setValue(count);
        if (position != null) for (double v : position) b.addVector(v);
        send(b.build());
    }

    /** Une action avec sa valeur, sa quantité et un vecteur (une position en Å, par exemple) ; vector peut être null. */
    public void send(UserAction.ActionType type, int value, double amount, double[] vector) {
        UserAction.Builder b = UserAction.newBuilder().setType(type).setValue(value).setAmount(amount);
        if (vector != null) for (double v : vector) b.addVector(v);
        send(b.build());
    }

    public void kickSubatomic(int particle, int sample, double vx, double vy, double vz) {
        send(UserAction.newBuilder().setType(UserAction.ActionType.SUBATOMIC_KICK)
                .setValue(particle).setAmount(sample)
                .addVector(vx).addVector(vy).addVector(vz).build());
    }

    /** Un coup sur un quark du nucléon : le quark (0–2), le transfert de moment (MeV/c) et sa direction. */
    public void strikeNucleon(int quark, double momentumMev, double dx, double dy, double dz) {
        send(UserAction.newBuilder().setType(UserAction.ActionType.NUCLEON_STRIKE)
                .setValue(quark).setAmount(momentumMev)
                .addVector(dx).addVector(dy).addVector(dz).build());
    }

    /** La mer de partons : Q² (GeV²) et plus petit x résolu ; `start` ouvre l'écran, sinon on change la résolution. */
    public void partonResolution(boolean start, double q2, double xMin) {
        send(UserAction.newBuilder().setType(start ? UserAction.ActionType.PARTONS_START : UserAction.ActionType.PARTONS_SET)
                .setAmount(q2).addVector(xMin).build());
    }

    /** Un électron sur un nucléon : cible (0 proton, 1 neutron), énergie du faisceau (MeV), angle de diffusion (°). */
    public void collide(int target, double energyMev, double angleDeg) {
        collide(target, energyMev, angleDeg, 0);
    }

    /**
     * Un projectile sur un nucléon : cible (0 proton, 1 neutron), énergie (MeV ; hadrons : impulsion MeV/c), angle (°),
     * projectile (0 e⁻, 1 e⁺, 2 μ⁻, 3 μ⁺, 4 ν_μ, 5 ν̄_μ, 6 p, 7 π⁺, 8 π⁻).
     */
    public void collide(int target, double energyMev, double angleDeg, int projectile) {
        send(UserAction.newBuilder().setType(UserAction.ActionType.COLLISION_RUN).setValue(target).setAmount(energyMev)
                .addVector(angleDeg).addVector(projectile).build());
    }

    /** L'atelier : l'état de spin choisi et les triplets (code de particule, antiparticule, couleur) posés. */
    public void testHadron(int spinIndex, double[] triples) {
        UserAction.Builder b = UserAction.newBuilder().setType(UserAction.ActionType.HADRON_TEST).setValue(spinIndex);
        for (double v : triples) b.addVector(v);
        send(b.build());
    }

    public void loadElement(int atomicNumber) {
        send(UserAction.ActionType.LOAD_ELEMENT, atomicNumber);
    }

    /** Un StreamObserver n'est pas sûr entre threads : les envois sont sérialisés. */
    private synchronized void send(UserAction action) {
        actions.onNext(action);
    }

    /**
     * Récupère la table périodique complète. Appel bloquant : à lancer depuis
     * un thread de fond, jamais depuis le thread JavaFX.
     */
    public PeriodicTable fetchPeriodicTable() {
        return UniverseEngineGrpc.newBlockingStub(channel)
                .withDeadlineAfter(8, TimeUnit.SECONDS)
                .getPeriodicTable(TableRequest.getDefaultInstance());
    }

    /**
     * Abandonne ce client sans rien attendre : sert à la reconnexion, qui
     * s'exécute sur le thread JavaFX et ne doit pas geler l'interface.
     */
    public void abandon() {
        try {
            actions.onCompleted();
        } catch (RuntimeException ignored) {
            // Flux déjà rompu : c'est précisément pour ça qu'on l'abandonne.
        }
        channel.shutdownNow();
    }

    /** Ferme proprement le flux puis le canal, à l'arrêt de l'application. */
    public void shutdown() {
        try {
            actions.onCompleted();
        } catch (RuntimeException ignored) {
            // Le flux peut déjà être rompu si le moteur est tombé : sans importance ici.
        }
        channel.shutdown();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
