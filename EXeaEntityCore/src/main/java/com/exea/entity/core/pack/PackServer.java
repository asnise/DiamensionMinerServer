package com.exea.entity.core.pack;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Serves the EXea model pack over HTTP and hands it to every player on join, so nobody installs it
 * by hand. The pack is the merge of:
 * <ol>
 *   <li>{@code exeapack/} inside the plugin jar (models bundled at build time), then</li>
 *   <li>{@code plugins/EXeaEntityCore/pack/} (models added without rebuilding: {@code exea_exporter.js --server}).
 *       A file there replaces the jar's file with the same path.</li>
 * </ol>
 * {@link #rebuild()} (on {@code /exea reload}) zips it again and resends it to online players when it changed.
 *
 * <p>It is sent with {@code addResourcePack} as its own pack id, on top of whatever else the server
 * sends. A plugin that uses {@code setResourcePack} makes the client drop every other pack: EXea
 * sends after it (later delay) and re-adds its pack whenever the client reports it as discarded.
 *
 * <p>config.yml: {@code resource_pack.enabled}, {@code .port} (8086), {@code .public_url},
 * {@code .required}, {@code .send_delay_ticks}.
 */
public final class PackServer implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("exea:models".getBytes(StandardCharsets.UTF_8));
    private static final String DEFAULT_MCMETA = """
            {"pack": {"pack_format": 75, "supported_formats": [46, 999], "min_format": 46, "max_format": 999,
              "description": "EXea entity models"}}
            """;

    private final JavaPlugin plugin;
    private File pluginJar;
    private HttpServer server;
    private volatile byte[] zip;
    private byte[] sha1;
    private String url;
    private String baseUrl;
    private boolean required;
    private long delay;

    public PackServer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(File pluginJar) {
        this.pluginJar = pluginJar;
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource_pack.enabled", true)) {
            plugin.getLogger().info("[Pack] Model pack disabled in config.");
            return;
        }
        int port = cfg.getInt("resource_pack.port", 8086);
        String base = cfg.getString("resource_pack.public_url", "");
        baseUrl = base == null || base.isBlank() ? "http://localhost:" + port + "/exea.zip" : base;
        required = cfg.getBoolean("resource_pack.required", false);
        delay = Math.max(1, cfg.getLong("resource_pack.send_delay_ticks", 40));
        if (!build()) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/exea.zip", ex -> {
                byte[] body = zip;
                if (body == null) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            });
            server.start();
            plugin.getLogger().info("[Pack] Serving model pack (" + zip.length / 1024 + " KiB) at " + url);
        } catch (IOException e) {
            plugin.getLogger().severe("[Pack] Could not start the pack server on port " + port + ": " + e.getMessage());
            zip = null;
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) send(p);
    }

    /**
     * Zips the pack again (after models were added to the data folder) and, if it changed, sends the
     * new one to every online player. Returns a short status line.
     */
    public String rebuild() {
        if (server == null) {
            return "pack server is not running";
        }
        byte[] oldSha = sha1;
        if (!build()) {
            return "could not build the pack (see console)";
        }
        if (oldSha != null && MessageDigest.isEqual(oldSha, sha1)) {
            return "pack unchanged";
        }
        for (Player p : Bukkit.getOnlinePlayers()) send(p);
        return "pack rebuilt (" + zip.length / 1024 + " KiB) and sent to " + Bukkit.getOnlinePlayers().size() + " player(s)";
    }

    public void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    /** The running HTTP server (the live link adds its /api/ paths to it), or null when the pack is off. */
    public HttpServer httpServer() {
        return server;
    }

    public void send(Player player) {
        if (zip == null || !player.isOnline()) return;
        player.addResourcePack(PACK_ID, url, sha1, "EXea 3D models (bosses and creatures)", required);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(p), delay);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) return;
        switch (event.getStatus()) {
            // Another plugin replaced the client's packs (setResourcePack): put ours back on top.
            case DISCARDED -> Bukkit.getScheduler().runTaskLater(plugin, () -> send(event.getPlayer()), 20L);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL ->
                    plugin.getLogger().info("[Pack] " + event.getPlayer().getName() + ": " + event.getStatus());
            default -> { }
        }
    }

    /** Builds {@link #zip}, {@link #sha1} and {@link #url}; false (and logs) if there is nothing to serve. */
    private boolean build() {
        try {
            Map<String, byte[]> files = collect(pluginJar, new File(plugin.getDataFolder(), "pack"));
            if (files.keySet().stream().noneMatch(n -> n.startsWith("assets/"))) {
                plugin.getLogger().warning("[Pack] No model pack: the jar has no exeapack/ and "
                        + new File(plugin.getDataFolder(), "pack").getPath() + " has no assets/ (export a model first).");
                return false;
            }
            files.putIfAbsent("pack.mcmeta", DEFAULT_MCMETA.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = zipOf(files);
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            zip = bytes;
            sha1 = digest;
            url = baseUrl + "?h=" + HexFormat.of().formatHex(digest).substring(0, 12);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Pack] Could not build the model pack: " + e.getMessage());
            return false;
        }
    }

    /** Pack path → bytes: the jar's exeapack/ entries, then the data folder pack on top. Sorted for a stable hash. */
    private static Map<String, byte[]> collect(File jar, File dataPack) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (jar != null && jar.isFile()) {
            try (ZipFile in = new ZipFile(jar)) {
                for (Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements(); ) {
                    ZipEntry z = e.nextElement();
                    if (z.isDirectory() || !z.getName().startsWith("exeapack/")) continue;
                    try (InputStream is = in.getInputStream(z)) {
                        files.put(z.getName().substring("exeapack/".length()), is.readAllBytes());
                    }
                }
            }
        }
        if (dataPack.isDirectory()) {
            Path root = dataPack.toPath();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    files.put(root.relativize(p).toString().replace(File.separatorChar, '/'), Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    private static byte[] zipOf(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                ZipEntry o = new ZipEntry(f.getKey());
                o.setTime(0);
                out.putNextEntry(o);
                out.write(f.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
