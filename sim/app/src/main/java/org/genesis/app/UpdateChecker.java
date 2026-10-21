package org.genesis.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La dernière version publiée sur GitHub (dépôt genesis-releases) : savoir s'il y a plus récent, et le récupérer.
 *
 * Aucune bibliothèque : une requête à l'API publique des versions, lue avec deux expressions régulières (le numéro et les
 * fichiers joints). Un installateur téléchargé n'est lancé que si son empreinte SHA-256 est celle publiée dans
 * CHECKSUMS-SHA256.txt. Hors ligne, ou si GitHub ne répond pas, rien ne se passe.
 */
public final class UpdateChecker {
    static final String RELEASES_API = "https://api.github.com/repos/Lightminedust/genesis-releases/releases/latest";
    static final String CHECKSUMS = "CHECKSUMS-SHA256.txt";

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PAGE = Pattern.compile("\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]+/releases/tag/[^\"]+)\"");
    private static final Pattern DOWNLOAD = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    private UpdateChecker() {
    }

    /** Un fichier joint à une version : son nom et son adresse de téléchargement. */
    public record Asset(String name, String url) {
    }

    /** Une version publiée : son numéro (sans « v »), sa page, ses fichiers. */
    public record Release(String version, String page, List<Asset> assets) {
        Optional<Asset> asset(String name) {
            return assets.stream().filter(a -> a.name().equals(name)).findFirst();
        }

        /** L'installateur de ce système, s'il en existe un qu'on sait lancer sans droits d'administrateur. */
        Optional<Asset> installer() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("win")) return Optional.empty();       // .deb : root ; .dmg : à glisser soi-même
            return assets.stream().filter(a -> a.name().endsWith(".exe")).findFirst();
        }
    }

    /**
     * La version qui tourne : celle donnée par jpackage à l'application empaquetée (ou -Dgenesis.version) ; null quand on
     * lance depuis les sources, où il n'y a rien à mettre à jour.
     */
    static String currentVersion() {
        String forced = System.getProperty("genesis.version");
        return forced != null && !forced.isBlank() ? forced : System.getProperty("jpackage.app-version");
    }

    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** La dernière version publiée, en arrière-plan ; vide si GitHub ne répond pas comme prévu. */
    static CompletableFuture<Optional<Release>> latest(HttpClient http, String current) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_API)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Genesis/" + (current == null ? "dev" : current)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200 ? parse(response.body()) : Optional.<Release>empty())
                .exceptionally(error -> Optional.empty());
    }

    static Optional<Release> parse(String json) {
        Matcher tag = TAG.matcher(json);
        if (!tag.find()) return Optional.empty();
        String version = tag.group(1).replaceFirst("^[vV]", "");
        Matcher page = PAGE.matcher(json);
        String url = page.find() ? page.group(1)
                : "https://github.com/Lightminedust/genesis-releases/releases/tag/" + tag.group(1);
        List<Asset> assets = new ArrayList<>();
        Matcher download = DOWNLOAD.matcher(json);
        while (download.find()) {
            String link = download.group(1);
            String name = URLDecoder.decode(link.substring(link.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
            assets.add(new Asset(name, link));
        }
        return Optional.of(new Release(version, url, List.copyOf(assets)));
    }

    /** Compare deux numéros de version nombre par nombre (0.10.0 > 0.9.2) ; négatif si a est plus ancien. */
    static int compare(String a, String b) {
        List<Integer> x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int left = i < x.size() ? x.get(i) : 0, right = i < y.size() ? y.get(i) : 0;
            if (left != right) return Integer.compare(left, right);
        }
        return 0;
    }

    private static List<Integer> numbers(String version) {
        List<Integer> out = new ArrayList<>();
        Matcher m = NUMBERS.matcher(version == null ? "" : version);
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    /** L'empreinte publiée pour `file` dans un fichier de sommes au format sha256sum (« EMPREINTE  nom »). */
    static Optional<String> expectedHash(String checksums, String file) {
        for (String line : checksums.split("\\R")) {
            String[] parts = line.trim().split("\\s+\\*?", 2);
            if (parts.length == 2 && parts[1].trim().equals(file) && parts[0].matches("(?i)[0-9a-f]{64}")) {
                return Optional.of(parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0;) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Télécharge l'installateur dans `folder`, vérifie son empreinte, et rend son chemin. `progress` reçoit la part
     * téléchargée (0 à 1). Lève une IOException si le fichier ne correspond pas à l'empreinte publiée.
     */
    static Path download(HttpClient http, Release release, Asset installer, Path folder, DoubleConsumer progress)
            throws IOException, InterruptedException {
        Asset sums = release.asset(CHECKSUMS)
                .orElseThrow(() -> new IOException("Pas d'empreintes publiées pour cette version : installation refusée."));
        String checksums = http.send(HttpRequest.newBuilder(URI.create(sums.url())).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String expected = expectedHash(checksums, installer.name())
                .orElseThrow(() -> new IOException("Aucune empreinte publiée pour " + installer.name() + " : installation refusée."));

        Files.createDirectories(folder);
        Path partial = folder.resolve(installer.name() + ".part");
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(installer.url()))
                .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Téléchargement refusé par GitHub (" + response.statusCode() + ")");
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buffer)) > 0;) {
                out.write(buffer, 0, n);
                done += n;
                if (total > 0) progress.accept((double) done / total);
            }
        }
        String actual = sha256(partial);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(partial);
            throw new IOException("Empreinte différente de celle publiée : fichier supprimé, rien n'est installé.");
        }
        Path target = folder.resolve(installer.name());
        return Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
