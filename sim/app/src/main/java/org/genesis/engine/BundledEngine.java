package org.genesis.engine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Démarre le moteur Python embarqué par le paquet Windows ou Linux et l'arrête avec la fenêtre. */
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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String engineName;
        if (os.startsWith("windows")) engineName = "GenesisEngine.exe";
        else if (os.startsWith("linux")) engineName = "GenesisEngine";
        else throw new IOException("Plateforme non prise en charge pour le moteur embarqué : " + os);
        Path executable = jar.getParent().resolve("engine").resolve(engineName);
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Moteur introuvable : " + executable);
        }

        // Chaque fenêtre obtient son propre port. Le serveur Python ne l'écoute que sur loopback.
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }

        Path logPath;
        Path cachePath;
        if (os.startsWith("windows")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path data = localAppData == null || localAppData.isBlank()
                    ? Path.of(System.getProperty("user.home"), "AppData", "Local", "Genesis")
                    : Path.of(localAppData, "Genesis");
            logPath = data.resolve("logs").resolve("engine.log");
            cachePath = data.resolve("cache");
        } else {
            Path home = Path.of(System.getProperty("user.home"));
            logPath = xdgDirectory("XDG_STATE_HOME", home.resolve(".local").resolve("state"))
                    .resolve("genesis").resolve("logs").resolve("engine.log");
            cachePath = xdgDirectory("XDG_CACHE_HOME", home.resolve(".cache"))
                    .resolve("genesis").resolve("cache");
        }
        Files.createDirectories(logPath.getParent());
        Files.createDirectories(cachePath);

        ProcessBuilder builder = new ProcessBuilder(executable.toString());
        builder.directory(executable.getParent().toFile());
        builder.environment().put("GRPC_HOST", "127.0.0.1");
        builder.environment().put("GRPC_PORT", Integer.toString(port));
        builder.environment().put("GENESIS_ENGINE_LOG", logPath.toString());
        builder.environment().put("GENESIS_CHEM_CACHE", cachePath.toString());
        builder.environment().remove("MONGO_URI");
        return new BundledEngine(builder.start(), port, logPath);
    }

    private static Path xdgDirectory(String variable, Path fallback) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) return fallback;
        Path path = Path.of(value);
        return path.isAbsolute() ? path : fallback;
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
