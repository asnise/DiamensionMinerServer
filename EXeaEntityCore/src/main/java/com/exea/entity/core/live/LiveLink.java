package com.exea.entity.core.live;

import com.exea.entity.EXeaEntityPlugin;
import com.exea.entity.api.SpawnOptions;
import com.exea.entity.api.effect.EffectOptions;
import com.exea.entity.core.effect.EffectManager;
import com.exea.entity.core.manager.EXActiveEntity;
import com.exea.entity.core.manager.EntityManager;
import com.exea.entity.core.skill.SkillManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Live link: lets a tool (EXea Studio) upload model / moveset / effect files and run a few test
 * commands on a running server over HTTP, on the same port as the model pack. Off by default.
 *
 * <pre>
 * GET  /api/status                         version, models, effects, movesets, online players
 * PUT  /api/file?path=models/x_bones.json  writes one file into plugins/EXeaEntityCore (allowed paths only)
 * POST /api/clear?model=x                  removes x's folders from the data-folder pack before an upload
 * POST /api/reload                         = /exea reload (models, effects, movesets, pack resent)
 * POST /api/spawn  {model, player?, ai?}   3 blocks in front of the player
 * POST /api/skill  {skill, model?, player?} the nearest entity with that skill casts it at the player
 * POST /api/fx     {id, player?, scale?}   plays an effect 2 blocks in front of the player
 * POST /api/anim   {name, model?, player?} the nearest entity plays the animation
 * POST /api/kill   {model?}                removes live entities (of that model)
 * </pre>
 * Every request needs {@code Authorization: Bearer <live_link.token>}. Unless {@code allow_remote},
 * only requests from this machine are accepted.
 */
public final class LiveLink {

    /** Files a tool may write, relative to the plugin data folder. */
    private static final Pattern ALLOWED = Pattern.compile(
            "models/[a-z0-9_\\-]+_(bones|anims|meta|moveset)\\.json"
                    + "|effects/[a-z0-9_\\-]+(/[a-z0-9_\\-]+)*\\.json"
                    + "|pack/pack\\.mcmeta"
                    + "|pack/assets/exea/(models/item|items|textures/item)/[a-z0-9_\\-]+/[a-z0-9_\\-]+\\.(json|png)");
    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9_\\-]+");

    private final EXeaEntityPlugin plugin;
    private final Gson gson = new Gson();
    private HttpServer ownServer;
    private HttpServer server;
    private byte[] token;
    private boolean allowRemote;
    private long maxUpload;

    public LiveLink(EXeaEntityPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("live_link.enabled", false)) return;
        String t = c.getString("live_link.token", "");
        if (t == null || t.length() < 16) {
            plugin.getLogger().warning("[Live] live_link.enabled is true but live_link.token is shorter than 16 characters; the live link stays off.");
            return;
        }
        token = t.getBytes(StandardCharsets.UTF_8);
        allowRemote = c.getBoolean("live_link.allow_remote", false);
        maxUpload = Math.max(1, c.getLong("live_link.max_upload_mb", 16)) * 1024 * 1024;
        server = plugin.getPackServer() != null ? plugin.getPackServer().httpServer() : null;
        int port = c.getInt("resource_pack.port", 8086);
        try {
            if (server == null) {
                ownServer = HttpServer.create(new InetSocketAddress(port), 0);
                ownServer.start();
                server = ownServer;
            }
            server.createContext("/api/", this::handle);
            plugin.getLogger().info("[Live] Live link on port " + port + (allowRemote ? " (remote allowed)" : " (this machine only)"));
        } catch (IOException e) {
            plugin.getLogger().severe("[Live] Could not start the live link on port " + port + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            try {
                server.removeContext("/api/");
            } catch (IllegalArgumentException ignored) {
                // already gone with the pack server
            }
        }
        if (ownServer != null) ownServer.stop(0);
        server = ownServer = null;
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!allowRemote && !ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                reply(ex, 403, error("remote access is off (live_link.allow_remote)"));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] given = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (!MessageDigest.isEqual(given, token)) {
                reply(ex, 401, error("wrong or missing token"));
                return;
            }
            String path = ex.getRequestURI().getPath().substring("/api/".length());
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String method = ex.getRequestMethod();
            JsonObject res = switch (path) {
                case "status" -> sync(this::status);
                case "file" -> method.equals("PUT") ? upload(q.get("path"), ex) : error("use PUT");
                case "clear" -> clear(q.get("model"));
                case "reload" -> sync(this::reload);
                case "spawn", "skill", "fx", "anim", "kill" -> {
                    JsonObject body = readJson(ex);
                    yield sync(() -> command(path, body));
                }
                default -> null;
            };
            if (res == null) reply(ex, 404, error("unknown endpoint /api/" + path));
            else reply(ex, res.has("error") ? 400 : 200, res);
        } catch (Exception e) {
            reply(ex, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private void reply(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] b = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return o;
    }

    private static JsonObject ok(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", msg);
        return o;
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(64 * 1024);
            String s = new String(b, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Runs on the main thread and waits (up to 10 s). */
    private JsonObject sync(Callable<JsonObject> c) throws Exception {
        if (Bukkit.isPrimaryThread()) return c.call();
        return Bukkit.getScheduler().callSyncMethod(plugin, c).get(10, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ files

    private JsonObject upload(String rel, HttpExchange ex) throws IOException {
        if (rel == null || !ALLOWED.matcher(rel).matches() || rel.contains("..")) return error("path not allowed: " + rel);
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) return error("path not allowed: " + rel);
        byte[] data;
        try (InputStream in = ex.getRequestBody()) {
            data = in.readNBytes((int) Math.min(Integer.MAX_VALUE, maxUpload + 1));
        }
        if (data.length > maxUpload) return error("file larger than live_link.max_upload_mb");
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return ok(rel + " (" + data.length + " bytes)");
    }

    private JsonObject clear(String model) throws IOException {
        if (model == null || !MODEL_ID.matcher(model).matches()) return error("bad model id");
        int n = 0;
        for (String sub : List.of("models/item", "items", "textures/item")) {
            File dir = new File(plugin.getDataFolder(), "pack/assets/exea/" + sub + "/" + model);
            if (!dir.isDirectory()) continue;
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                    n++;
                }
            }
        }
        return ok("removed " + n + " pack file(s) of " + model);
    }

    // ------------------------------------------------------------------ commands (main thread)

    private JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("version", plugin.getDescription().getVersion());
        o.add("models", strings(EntityManager.getInstance().getTemplateIds()));
        o.add("effects", strings(List.copyOf(EffectManager.get().ids())));
        o.add("movesets", strings(List.copyOf(SkillManager.get().ids())));
        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
        o.add("players", players);
        int alive = 0;
        for (EXActiveEntity e : EntityManager.getInstance().getActiveEntities().values()) if (e.isValid()) alive++;
        o.addProperty("entities", alive);
        return o;
    }

    private JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private JsonObject reload() {
        EntityManager.getInstance().reload(plugin);
        EffectManager.get().reload();
        SkillManager.get().reload();
        String pack = plugin.getPackServer() != null ? plugin.getPackServer().rebuild() : "pack off";
        plugin.getLogger().info("[Live] reload: " + pack);
        JsonObject o = status();
        o.addProperty("pack", pack);
        return o;
    }

    private Player player(JsonObject b) {
        String name = b.has("player") ? b.get("player").getAsString() : null;
        if (name != null && !name.isBlank()) return Bukkit.getPlayerExact(name);
        return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
    }

    private JsonObject command(String what, JsonObject b) {
        EntityManager em = EntityManager.getInstance();
        Player p = player(b);
        String model = b.has("model") ? b.get("model").getAsString().toLowerCase() : null;
        if (what.equals("kill")) {
            int n = 0;
            for (EXActiveEntity e : List.copyOf(em.getActiveEntities().values())) {
                if (e.isValid() && (model == null || e.getModelId().equals(model))) {
                    e.remove();
                    n++;
                }
            }
            return ok("removed " + n);
        }
        if (p == null) return error("no player online" + (b.has("player") ? " named " + b.get("player").getAsString() : ""));
        Location eye = p.getLocation();
        org.bukkit.util.Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1e-6) ahead = new org.bukkit.util.Vector(0, 0, 1);
        ahead.normalize();
        plugin.getLogger().info("[Live] " + what + " " + b + " at " + p.getName());
        switch (what) {
            case "spawn" -> {
                if (model == null || !em.getTemplateIds().contains(model)) return error("unknown model " + model + " (reload first?)");
                Location at = eye.clone().add(ahead.clone().multiply(3));
                at.setYaw(eye.getYaw() + 180); // facing the player
                at.setPitch(0);
                boolean ai = !b.has("ai") || b.get("ai").getAsBoolean();
                return em.spawn(model, at, SpawnOptions.create().ai(ai)) != null ? ok("spawned " + model) : error("could not spawn " + model);
            }
            case "fx" -> {
                String id = b.has("id") ? b.get("id").getAsString().toLowerCase() : "";
                Location at = eye.clone().add(ahead.clone().multiply(2));
                at.setPitch(0);
                double scale = b.has("scale") ? b.get("scale").getAsDouble() : 1;
                return EffectManager.get().play(id, at, EffectOptions.create().scale(scale).yaw(eye.getYaw()).target(p)) != null
                        ? ok("playing " + id) : error("unknown effect " + id + " (reload first?)");
            }
            case "skill", "anim" -> {
                String name = (what.equals("skill") ? (b.has("skill") ? b.get("skill").getAsString() : "") : (b.has("name") ? b.get("name").getAsString() : "")).toLowerCase();
                EXActiveEntity best = null;
                double bestD = Double.MAX_VALUE;
                for (EXActiveEntity e : em.getActiveEntities().values()) {
                    if (!e.isValid() || e.isDying() || (model != null && !e.getModelId().equals(model))) continue;
                    if (what.equals("skill") ? !e.getSkills().contains(name) : !e.getAnimations().contains(name)) continue;
                    if (e.getDriver().getWorld() != p.getWorld()) continue;
                    double d = e.getDriver().getLocation().distanceSquared(eye);
                    if (d < bestD) { bestD = d; best = e; }
                }
                if (best == null) return error("no live entity" + (model != null ? " of " + model : "") + " has " + what + " " + name + " in " + p.getName() + "'s world");
                if (what.equals("anim")) {
                    best.playAnimation(name);
                    return ok(best.getModelId() + " plays " + name);
                }
                best.cancelSkill();
                best.setCooldown(name, 0);
                return best.castSkill(name, p) ? ok(best.getModelId() + " casts " + name + " at " + p.getName()) : error("could not cast " + name + " (a plugin cancelled it?)");
            }
            default -> {
                return error("unknown command");
            }
        }
    }
}
