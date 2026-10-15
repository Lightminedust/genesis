package org.genesis.engine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Démarre le moteur Python embarqué par le paquet Windows et l'arrête avec la fenêtre. */
public final class BundledEngine implements AutoCloseable {
    private final Process process;
    private final int port;
    private final Path logPath;

    private BundledEngine(Process process, int port, Path logPath) {
        this.process = process;
        this.port = port;
        this.logPath = logPath;
    }

    public int port() {
        return port;
    }

    public Path logPath() {
        return logPath;
    }

    /** Attend que les imports Python et les données nucléaires soient chargés, hors du thread JavaFX. */
    public void awaitReady(long timeoutSeconds) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        IOException lastError = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("Le moteur Python s'est arrêté (code " + process.exitValue()
                        + "). Journal : " + logPath, lastError);
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 750);
                return;
            } catch (IOException error) {
                lastError = error;
            }
            Thread.sleep(250);
        }
        throw new IOException("Le moteur Python ne répond pas après " + timeoutSeconds
                + " s. Journal : " + logPath, lastError);
    }

    public static BundledEngine start() throws IOException {
        Path jar;
        try {
            jar = Path.of(BundledEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException error) {
            throw new IOException("Impossible de localiser le moteur embarqué", error);
        }
        Path executable = jar.getParent().resolve("engine").resolve("GenesisEngine.exe");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Moteur introuvable : " + executable);
        }

        // Chaque fenêtre obtient son propre port. Le serveur Python ne l'écoute que sur loopback.
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        Path data = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local", "Genesis")
                : Path.of(localAppData, "Genesis");
        Files.createDirectories(data.resolve("logs"));
        Files.createDirectories(data.resolve("cache"));

        ProcessBuilder builder = new ProcessBuilder(executable.toString());
        builder.directory(executable.getParent().toFile());
        builder.environment().put("GRPC_HOST", "127.0.0.1");
        builder.environment().put("GRPC_PORT", Integer.toString(port));
        builder.environment().put("GENESIS_CHEM_CACHE", data.resolve("cache").toString());
        builder.environment().remove("MONGO_URI");
        return new BundledEngine(builder.start(), port, data.resolve("logs").resolve("engine.log"));
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
