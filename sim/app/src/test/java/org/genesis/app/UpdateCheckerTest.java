package org.genesis.app;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** La vérification de version : lecture de la réponse GitHub, comparaison, et téléchargement vérifié par empreinte. */
class UpdateCheckerTest {
    private static final String RELEASE = """
            {"url":"https://api.github.com/repos/Lightminedust/genesis-releases/releases/1",
             "html_url":"https://github.com/Lightminedust/genesis-releases/releases/tag/v0.3.0",
             "author":{"html_url":"https://github.com/Lightminedust"},
             "tag_name":"v0.3.0","name":"Genesis 0.3.0",
             "assets":[{"name":"CHECKSUMS-SHA256.txt","browser_download_url":"https://github.com/Lightminedust/genesis-releases/releases/download/v0.3.0/CHECKSUMS-SHA256.txt"},
                       {"name":"Genesis-0.3.0.exe","browser_download_url":"https://github.com/Lightminedust/genesis-releases/releases/download/v0.3.0/Genesis-0.3.0.exe"}]}
            """;

    @Test void readsTheLatestRelease() {
        UpdateChecker.Release release = UpdateChecker.parse(RELEASE).orElseThrow();
        assertEquals("0.3.0", release.version());
        assertEquals("https://github.com/Lightminedust/genesis-releases/releases/tag/v0.3.0", release.page());
        assertEquals(List.of("CHECKSUMS-SHA256.txt", "Genesis-0.3.0.exe"), release.assets().stream().map(UpdateChecker.Asset::name).toList());
        assertTrue(UpdateChecker.parse("{\"message\":\"Not Found\"}").isEmpty());
    }

    @Test void comparesVersionsNumberByNumber() {
        assertTrue(UpdateChecker.compare("0.10.0", "0.9.2") > 0);
        assertTrue(UpdateChecker.compare("0.2.0", "0.2.1") < 0);
        assertEquals(0, UpdateChecker.compare("0.2", "0.2.0"));
        assertTrue(UpdateChecker.compare("1.0.0", "0.99.99") > 0);
    }

    @Test void findsThePublishedHash() {
        String sums = "DA772465AF4AFE1BC522E34D6A5104E03C03D6A5C54AAEE5F1E48FC6EE6FAB24  Genesis-0.2.0.exe\r\n"
                + "AD27A29C4C72DEE7D9CA77239E94F7277C61D125A80C3208BCFCE12437217B64  Genesis-0.2.0-portable.zip\r\n";
        assertEquals("da772465af4afe1bc522e34d6a5104e03c03d6a5c54aaee5f1e48fc6ee6fab24",
                UpdateChecker.expectedHash(sums, "Genesis-0.2.0.exe").orElseThrow());
        assertTrue(UpdateChecker.expectedHash(sums, "Genesis-0.2.0-linux-x64.deb").isEmpty());
    }

    @Test void installsOnlyWhatMatchesThePublishedHash(@TempDir Path folder) throws Exception {
        byte[] installer = "un installateur".getBytes(StandardCharsets.UTF_8);
        String good = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(installer));
        String[] sums = {good + "  Genesis-0.3.0.exe\n"};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sums", exchange -> {
            byte[] body = sums[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/exe", exchange -> {
            exchange.sendResponseHeaders(200, installer.length);
            exchange.getResponseBody().write(installer);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            UpdateChecker.Asset exe = new UpdateChecker.Asset("Genesis-0.3.0.exe", base + "/exe");
            UpdateChecker.Release release = new UpdateChecker.Release("0.3.0", base,
                    List.of(new UpdateChecker.Asset(UpdateChecker.CHECKSUMS, base + "/sums"), exe));
            double[] last = {0};
            Path file = UpdateChecker.download(UpdateChecker.client(), release, exe, folder, f -> last[0] = f);
            assertArrayEquals(installer, Files.readAllBytes(file));
            assertEquals(1.0, last[0], 1e-9);

            // Une empreinte qui ne correspond pas : le fichier est supprimé, rien n'est rendu.
            sums[0] = "0".repeat(64) + "  Genesis-0.3.0.exe\n";
            Files.delete(file);
            IOException refused = assertThrows(IOException.class,
                    () -> UpdateChecker.download(UpdateChecker.client(), release, exe, folder, f -> { }));
            assertTrue(refused.getMessage().contains("Empreinte"), refused.getMessage());
            try (var left = Files.list(folder)) {
                assertEquals(0, left.count());
            }
        } finally {
            server.stop(0);
        }
    }
}
