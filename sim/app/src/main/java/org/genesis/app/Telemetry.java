package org.genesis.app;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.prefs.Preferences;

/**
 * Les statistiques d'utilisation, envoyées au serveur de Gemmie (gemmi-e.com/genesis/stats) pour faire évoluer Genesis :
 * quand le logiciel s'ouvre et se ferme, combien de temps il sert (et combien au premier plan), quels laboratoires et
 * combien de temps dans chacun, sa version, le système, la langue, l'écran. Rien de ce que l'utilisateur construit ou
 * calcule.
 *
 * Un identifiant aléatoire (UUID) est créé à la première ouverture et rangé dans les préférences de l'utilisateur (le
 * registre sous Windows) : il survit aux mises à jour et aux réinstallations. Ni nom ni adresse ; l'adresse IP, c'est
 * le serveur qui la voit (gemme-stats : il en garde le pays et la ville donnés par Cloudflare, et une empreinte salée,
 * jamais l'adresse).
 *
 * Actif par défaut. La carte du premier lancement (TelemetryNotice) le dit et permet de refuser : refusé, plus rien
 * n'est jamais envoyé. Tant que la carte n'a pas de réponse, les envois de cette première session attendent ; sans
 * refus, ils partent à la fermeture.
 *
 * Le format est du JSON, jamais du SQL : un client qui enverrait des requêtes laisserait n'importe qui écrire dans la
 * base ; le serveur insère lui-même, avec des requêtes préparées. Un envoi qui échoue (hors ligne) attend dans une file,
 * renvoyée au lancement suivant ; la fin de la session est notée chaque minute, pour qu'un arrêt brutal ne la perde pas
 * (elle part au lancement suivant, avec clean = false).
 *
 * Sans version (lancé depuis les sources) ou avec -Dgenesis.telemetry=off (GENESIS_TELEMETRY=off), rien n'est envoyé :
 * les essais de développement ne se mêlent pas aux statistiques. -Dgenesis.telemetry=on force l'envoi, et
 * -Dgenesis.telemetry.endpoint=http://localhost:3000/genesis/stats l'envoie à un serveur d'essai.
 */
public final class Telemetry {
    static final String ENDPOINT = "https://gemmi-e.com/genesis/stats";
    static final int FORMAT = 1;
    static final String ID = "telemetry.id", ENABLED = "telemetry.enabled", NOTICE = "telemetry.notice",
            QUEUE = "telemetry.queue", PENDING = "telemetry.pending";
    /** Les préférences gardent au plus 8 192 caractères par valeur : la file tient sous cette taille. */
    static final int QUEUE_CHARS = 8000;
    /** Toutes les combien de secondes la fin de la session est notée (contre un arrêt brutal). */
    static final double CHECKPOINT_S = 60;

    /** Où ranger l'état : les préférences de l'utilisateur ; les tests en donnent une mémoire. */
    interface Store {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    /** Où envoyer : le serveur ; les tests le remplacent. Vrai si le serveur a accepté. */
    interface Sender {
        boolean send(String json, Duration timeout);
    }

    private final Store store;
    private final Sender sender;
    private final boolean allowed;
    private final String version;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "genesis-telemetry");
        thread.setDaemon(true);
        return thread;
    });
    /** Un seul envoi à la fois : sans lui, l'envoi d'arrière-plan et celui de la fermeture partaient en double. */
    private final ReentrantLock sending = new ReentrantLock();
    /** Ce que l'écran et la langue ajoutent à chaque envoi (posé par l'application). */
    private final Map<String, Object> context = new LinkedHashMap<>();
    private String install;
    private boolean firstRun;
    private String session;
    private OffsetDateTime started;
    private boolean running;
    /** La carte du premier lancement n'a pas encore de réponse : les envois attendent. */
    private boolean held;
    private double seconds, activeSeconds, sinceCheckpoint;
    private String lab;
    private final Map<String, double[]> labs = new LinkedHashMap<>();       // laboratoire → {ouvertures, secondes}

    Telemetry(Store store, Sender sender, String version, boolean allowed) {
        this.store = store;
        this.sender = sender;
        this.version = version == null ? "dev" : version;
        this.allowed = allowed;
    }

    /** La télémétrie de l'application : les préférences de l'utilisateur, le serveur de Gemmie. */
    static Telemetry create() {
        String version = UpdateChecker.currentVersion();
        String forced = System.getProperty("genesis.telemetry", System.getenv().getOrDefault("GENESIS_TELEMETRY", ""));
        boolean allowed = forced.equalsIgnoreCase("on") || (version != null && !forced.equalsIgnoreCase("off"));
        return new Telemetry(preferences(), http(version), version, allowed);
    }

    private static Store preferences() {
        Preferences node = Preferences.userNodeForPackage(App.class);
        return new Store() {
            @Override public String get(String key) { return node.get(key, null); }
            @Override public void put(String key, String value) { node.put(key, value); }
            @Override public void remove(String key) { node.remove(key); }
        };
    }

    /** L'adresse du serveur ; -Dgenesis.telemetry.endpoint en donne une autre (essayer son serveur en local). */
    static String endpoint() {
        return System.getProperty("genesis.telemetry.endpoint", ENDPOINT);
    }

    static Sender http(String version) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        URI target = URI.create(endpoint());
        return (json, timeout) -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(target).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Genesis/" + (version == null ? "dev" : version))
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build();
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                return status >= 200 && status < 300;
            } catch (java.io.IOException error) {
                return false;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }

    // ================================================================== le choix de l'utilisateur
    /** L'envoi est-il permis ? Faux après un refus, et toujours faux pour une version de développement. */
    boolean enabled() {
        return allowed && !"false".equals(store.get(ENABLED));
    }

    /** Peut-on même proposer l'envoi (pas une version de développement) ? */
    boolean available() {
        return allowed;
    }

    /** La carte du premier lancement reste à montrer. */
    boolean noticeNeeded() {
        return allowed && store.get(NOTICE) == null;
    }

    /** La réponse à la carte : d'accord (ce qui attendait part), ou refusé (plus rien, jamais). */
    synchronized void answer(boolean accept) {
        store.put(NOTICE, OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        setEnabled(accept);
    }

    /** L'interrupteur des paramètres. Coupé, la file est vidée ; rallumé, une session commence maintenant. */
    synchronized void setEnabled(boolean on) {
        store.put(ENABLED, Boolean.toString(on));
        if (!on) {
            running = false;
            held = false;
            store.remove(QUEUE);
            store.remove(PENDING);
            return;
        }
        held = false;
        if (!running) start();
        else flushLater();
    }

    /** L'identifiant de l'installation (créé s'il n'existe pas encore). */
    synchronized String install() {
        if (install == null) {
            install = store.get(ID);
            if (install == null || !isUuid(install)) {
                install = UUID.randomUUID().toString();
                store.put(ID, install);
                firstRun = true;
            }
        }
        return install;
    }

    /** Ce que l'application ajoute à chaque envoi : l'écran, la langue de l'interface. */
    synchronized void context(String key, Object value) {
        context.put(key, value);
    }

    // ================================================================== la session
    /** L'ouverture : une session commence, et ce qui attendait d'une session précédente repart. */
    synchronized void start() {
        if (!enabled() || running) return;
        install();
        String crashed = store.get(PENDING);
        if (crashed != null) {
            enqueue(crashed);                    // la fin d'une session qui s'est arrêtée sans fermer
            store.remove(PENDING);
        }
        session = UUID.randomUUID().toString();
        started = OffsetDateTime.now();
        seconds = activeSeconds = sinceCheckpoint = 0;
        lab = null;
        labs.clear();
        running = true;
        held = noticeNeeded();
        enqueue(event("start", false));
        if (!held) flushLater();
    }

    /** Chaque image : le laboratoire montré, le temps écoulé, la fenêtre au premier plan ou non. */
    synchronized void tick(String current, double dt, boolean focused) {
        if (!running || dt <= 0) return;
        seconds += dt;
        if (focused) activeSeconds += dt;
        if (current != null) {
            double[] use = labs.computeIfAbsent(current, k -> new double[2]);
            if (!current.equals(lab)) use[0]++;
            use[1] += dt;
        }
        lab = current;
        sinceCheckpoint += dt;
        if (sinceCheckpoint >= CHECKPOINT_S) {
            sinceCheckpoint = 0;
            store.put(PENDING, event("end", false));
        }
    }

    /** La fermeture : la fin de la session part (au plus `timeout`, pour ne pas retenir la fenêtre). */
    void stop(Duration timeout) {
        synchronized (this) {
            if (!running) return;
            running = false;
            store.remove(PENDING);
            enqueue(event("end", true));
            held = false;                        // sans refus pendant la session : l'envoi par défaut s'applique
        }
        worker.shutdown();
        flush(timeout);
    }

    // ================================================================== les envois
    /** Un envoi en JSON : l'ouverture (start) ou la fin (end) de la session, avec son contexte. */
    synchronized String event(String kind, boolean clean) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("format", FORMAT);
        e.put("event", kind);
        e.put("install", install());
        e.put("session", session);
        e.put("first_run", firstRun);
        e.put("time", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        e.put("started", started == null ? null : started.truncatedTo(ChronoUnit.SECONDS).toString());
        e.put("timezone", ZoneId.systemDefault().getId());
        e.put("version", version);
        e.put("os", System.getProperty("os.name", ""));
        e.put("os_version", System.getProperty("os.version", ""));
        e.put("arch", System.getProperty("os.arch", ""));
        e.put("java", System.getProperty("java.version", ""));
        e.put("locale", Locale.getDefault().toLanguageTag());
        e.put("cpus", Runtime.getRuntime().availableProcessors());
        e.put("memory_gb", memoryGb());
        e.putAll(context);
        if (kind.equals("end")) {
            e.put("seconds", Math.round(seconds));
            e.put("active_seconds", Math.round(activeSeconds));
            e.put("clean", clean);
            List<Map<String, Object>> list = new ArrayList<>();
            for (var use : labs.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lab", use.getKey());
                row.put("opens", Math.round(use.getValue()[0]));
                row.put("seconds", Math.round(use.getValue()[1]));
                list.add(row);
            }
            e.put("labs", list);
        }
        return json(e);
    }

    /** Ajoute à la file (les plus anciens tombent si elle déborde). */
    private synchronized void enqueue(String json) {
        List<String> queue = queue();
        queue.add(json);
        int total = 0;
        for (String item : queue) total += item.length() + 1;
        while (queue.size() > 1 && total > QUEUE_CHARS) total -= queue.remove(0).length() + 1;
        store.put(QUEUE, String.join("\n", queue));
    }

    synchronized List<String> queue() {
        String raw = store.get(QUEUE);
        List<String> out = new ArrayList<>();
        if (raw != null) for (String line : raw.split("\n")) if (!line.isBlank()) out.add(line);
        return out;
    }

    private void flushLater() {
        if (!worker.isShutdown()) worker.submit(() -> flush(Duration.ofSeconds(8)));
    }

    /** Envoie la file dans l'ordre ; s'arrête au premier échec (réessayé au prochain lancement). */
    void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!sending.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) return;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            send(deadline);
        } finally {
            sending.unlock();
        }
    }

    private void send(long deadline) {
        while (true) {
            String next;
            synchronized (this) {
                if (!enabled() || held) return;
                List<String> queue = queue();
                if (queue.isEmpty()) return;
                next = queue.get(0);
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || !sender.send(next, Duration.ofNanos(left))) return;
            synchronized (this) {
                List<String> queue = queue();
                if (!queue.isEmpty() && queue.get(0).equals(next)) queue.remove(0);
                if (queue.isEmpty()) store.remove(QUEUE);
                else store.put(QUEUE, String.join("\n", queue));
            }
        }
    }

    private static double memoryGb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return Math.round(os.getTotalMemorySize() / 1e8) / 10.0;
        } catch (RuntimeException | LinkageError error) {
            return 0;
        }
    }

    static boolean isUuid(String s) {
        return s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ================================================================== un JSON minimal (pas de bibliothèque)
    static String json(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object value) {
        if (value == null) {
            b.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            b.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            b.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (value instanceof Map<?, ?> map) {
            b.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (value instanceof List<?> list) {
            b.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) b.append(',');
                write(b, list.get(i));
            }
            b.append(']');
        } else {
            b.append('"');
            for (char c : String.valueOf(value).toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }
    }
}
