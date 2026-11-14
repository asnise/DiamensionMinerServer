package com.exea.entity.core.effect;

import com.exea.entity.api.effect.EffectLayerHandler;
import com.exea.entity.api.effect.EffectOptions;
import com.exea.entity.core.effect.layer.CustomLayer;
import com.exea.entity.core.effect.layer.PresetLayer;
import com.exea.entity.core.manager.EXActiveEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Loads effect presets ({@code effects/*.json} from the jar, then {@code plugins/EXeaEntityCore/effects/},
 * which wins on the same id), plays them, ticks them, and enforces the budget in config {@code effects:}.
 * Main thread only.
 */
public final class EffectManager {

    private static final EffectManager INSTANCE = new EffectManager();
    public static final String TAG = "exea_fx";
    private static final int MAX_DEPTH = 4;

    public static EffectManager get() {
        return INSTANCE;
    }

    private Plugin plugin;
    private final Map<String, EffectDefinition> defs = new TreeMap<>();
    private final Map<String, EffectLayerHandler> customLayers = new HashMap<>();
    private final List<EffectInstance> active = new ArrayList<>();
    private final Set<String> warned = new HashSet<>();
    private BukkitTask task;

    // budget (config effects:)
    int maxEntitiesPerEffect = 48;
    int maxEntities = 600;
    int maxParticlesPerTick = 4000;
    double viewDistance = 48;
    private int entities;
    private int particlesThisTick;

    public void start(Plugin plugin) {
        this.plugin = plugin;
        readConfig();
        reload();
        if (task == null) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        stopAll();
    }

    private void readConfig() {
        var c = plugin.getConfig();
        maxEntitiesPerEffect = c.getInt("effects.max_entities_per_effect", 48);
        maxEntities = c.getInt("effects.max_entities", 600);
        maxParticlesPerTick = c.getInt("effects.max_particles_per_tick", 4000);
        viewDistance = c.getDouble("effects.view_distance", 48);
    }

    // ------------------------------------------------------------------ loading

    /** Reloads every effect file. Running effects keep the definition they started with. */
    public void reload() {
        if (plugin == null) return;
        plugin.reloadConfig();
        readConfig();
        defs.clear();
        warned.clear();
        Map<String, String> from = new TreeMap<>();
        try {
            java.net.URL jarUrl = EffectManager.class.getProtectionDomain().getCodeSource().getLocation();
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new File(jarUrl.toURI()))) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("effects/") && name.endsWith(".json")) {
                        String id = name.substring("effects/".length(), name.length() - 5);
                        try (InputStream in = plugin.getResource(name)) {
                            if (in != null && load(id, in, "jar:" + name)) from.put(id, "jar");
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] Could not list effects in the plugin jar: " + e.getMessage());
        }
        Path dir = new File(plugin.getDataFolder(), "effects").toPath();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
                    String rel = dir.relativize(p).toString().replace(File.separatorChar, '/');
                    String id = rel.substring(0, rel.length() - 5).toLowerCase();
                    try (InputStream in = Files.newInputStream(p)) {
                        if (load(id, in, "effects/" + rel)) from.put(id, "disk");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[EXea] Could not read " + dir + ": " + e.getMessage());
            }
        }
        checkReferences();
        plugin.getLogger().info("[EXea] Effects: " + defs.size() + " (" + from.values().stream().filter("disk"::equals).count() + " from the data folder)");
    }

    private boolean load(String id, InputStream in, String where) {
        if (!id.matches("[a-z0-9_\\-/]+")) {
            plugin.getLogger().warning("[EXea] " + where + ": id \"" + id + "\" must be lower case a-z 0-9 _ - /; skipped");
            return false;
        }
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject j = JsonParser.parseReader(r).getAsJsonObject();
            Consumer<String> warn = msg -> plugin.getLogger().warning("[EXea] " + where + ": " + msg);
            EffectDefinition d = EffectDefinition.parse(id, j, warn);
            if (d == null) return false;
            defs.put(id, d);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[EXea] " + where + ": not valid JSON (" + e.getMessage() + "); skipped");
            return false;
        }
    }

    /** Missing preset ids and preset loops (§0 load-time checks); a looping chain is dropped. */
    private void checkReferences() {
        for (EffectDefinition d : List.copyOf(defs.values())) {
            for (EffectDefinition.Layer l : d.layers) {
                if (l.template instanceof PresetLayer.Template p && !defs.containsKey(p.id())) {
                    plugin.getLogger().warning("[EXea] effect " + d.id + ": preset layer refers to unknown effect \"" + p.id() + "\"");
                }
            }
            if (loops(d.id, new LinkedHashSet<>())) {
                plugin.getLogger().warning("[EXea] effect " + d.id + ": preset layers form a loop; effect removed");
                defs.remove(d.id);
            }
        }
    }

    private boolean loops(String id, LinkedHashSet<String> path) {
        if (!path.add(id)) return true;
        EffectDefinition d = defs.get(id);
        if (d != null) for (EffectDefinition.Layer l : d.layers) {
            if (l.template instanceof PresetLayer.Template p && loops(p.id(), path)) return true;
        }
        path.remove(id);
        return false;
    }

    public Set<String> ids() {
        return defs.keySet();
    }

    public EffectDefinition get(String id) {
        return id == null ? null : defs.get(id.toLowerCase());
    }

    public void registerLayer(String type, EffectLayerHandler handler) {
        if (handler == null) customLayers.remove(type);
        else customLayers.put(type, handler);
    }

    // ------------------------------------------------------------------ playing

    /** Plays at a place. Null for an unknown id (logged once). */
    public EffectInstance play(String id, Location at, EffectOptions o) {
        if (at == null || at.getWorld() == null) return null;
        EffectOptions opts = o != null ? o : EffectOptions.create();
        float yaw = opts.getYaw() != null ? opts.getYaw() : at.getYaw();
        return play(id, Origin.fixed(at, yaw), opts, 0);
    }

    /** Plays on an entity (its feet). */
    public EffectInstance play(String id, Entity on, EffectOptions o) {
        EffectOptions opts = o != null ? o : EffectOptions.create();
        Origin origin = opts.isFollow() ? Origin.entity(on, opts.getYaw())
                : Origin.fixed(on.getLocation(), opts.getYaw() != null ? opts.getYaw() : Origin.facing(on));
        return play(id, origin, opts, 0);
    }

    /** Plays on a bone of an EXea entity (its pivot, following the animation). */
    public EffectInstance play(String id, EXActiveEntity on, String bone, EffectOptions o) {
        EffectOptions opts = o != null ? o : EffectOptions.create();
        Origin origin = Origin.bone(on, bone, opts.getYaw());
        if (!opts.isFollow()) origin = Origin.fixed(origin.location(), origin.yaw());
        return play(id, origin, opts, 0);
    }

    /** Plays on any origin (a projectile, a moving point). */
    public EffectInstance play(String id, Origin origin, EffectOptions opts) {
        return play(id, origin, opts != null ? opts : EffectOptions.create(), 0);
    }

    /** Plays a definition that is not in the registry (one built for a skill action). */
    public EffectInstance play(EffectDefinition d, Origin origin, EffectOptions opts) {
        EffectOptions o = opts != null ? opts : EffectOptions.create();
        if (d == null || origin.location() == null) return null;
        EffectInstance inst = new EffectInstance(d, origin, o.getScale(), o.getTarget(), o.getTags(), 0);
        active.add(inst);
        return inst;
    }

    EffectInstance play(String id, Origin origin, EffectOptions opts, int depth) {
        EffectDefinition d = get(id);
        if (d == null) {
            warnOnce("unknown:" + id, "unknown effect \"" + id + "\" (loaded: " + defs.size() + "; /exea fx list)");
            return null;
        }
        if (depth > MAX_DEPTH || origin.location() == null) return null;
        EffectInstance inst = new EffectInstance(d, origin, opts.getScale(), opts.getTarget(), opts.getTags(), depth);
        active.add(inst);
        return inst;
    }

    /** A child effect for a {@code preset} layer, ticked by that layer. */
    public EffectInstance playChild(EffectInstance parent, String id, Origin origin, double scale) {
        EffectDefinition d = get(id);
        if (d == null || parent.depth >= MAX_DEPTH) return null;
        return new EffectInstance(d, origin, parent.scale * scale, parent.target, parent.tags, parent.depth + 1);
    }

    /** Ticks a child effect (owned by a preset layer, not in the active list). */
    public boolean tickChild(EffectInstance child) {
        return child.tick();
    }

    LayerRun startLayer(EffectInstance inst, EffectDefinition.Layer l) {
        if (l.template != null) return l.template.start(inst, l);
        EffectLayerHandler h = customLayers.get(l.type);
        if (h == null) {
            warnOnce("type:" + l.type, "effect " + inst.def.id + ": layer type \"" + l.type + "\" is not built in and no plugin registered it");
            return null;
        }
        return new CustomLayer(inst, l, h);
    }

    /** Effects within {@code radius} of a place (looping ones included). */
    public int stopNear(Location at, double radius) {
        int n = 0;
        for (EffectInstance e : List.copyOf(active)) {
            Location l = e.origin.location();
            if (l != null && l.getWorld() == at.getWorld() && l.distanceSquared(at) <= radius * radius) {
                e.stop();
                n++;
            }
        }
        return n;
    }

    /** Stops every effect that plays on this entity (removal, death). */
    public void stopOn(Entity e) {
        for (EffectInstance i : List.copyOf(active)) if (i.origin.entity() == e) i.stopLooping();
    }

    public void stopAll() {
        for (EffectInstance e : active) e.stop();
        active.clear();
        entities = 0;
    }

    public int activeCount() {
        return active.size();
    }

    public int entityCount() {
        return entities;
    }

    private void tick() {
        particlesThisTick = 0;
        for (int i = 0; i < active.size(); i++) {
            EffectInstance e = active.get(i);
            boolean alive;
            try {
                alive = e.tick();
            } catch (RuntimeException ex) {
                warnOnce("tick:" + e.def.id, "effect " + e.def.id + " failed and was stopped: " + ex);
                e.stop();
                alive = false;
            }
            if (!alive) {
                active.remove(i--);
            }
        }
    }

    // ------------------------------------------------------------------ budget helpers for layers

    /** Spawns a non-persistent display owned by {@code inst}, or null when over budget. */
    public <T extends Display> T spawnDisplay(EffectInstance inst, Location at, Class<T> type, Consumer<T> setup) {
        if (inst.entities >= maxEntitiesPerEffect || entities >= maxEntities) return null;
        T d = at.getWorld().spawn(at, type, e -> {
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            setup.accept(e);
        });
        inst.entities++;
        entities++;
        return d;
    }

    public void release(EffectInstance inst, Entity e) {
        if (e == null) return;
        e.remove();
        inst.entities = Math.max(0, inst.entities - 1);
        entities = Math.max(0, entities - 1);
    }

    /** How many of {@code wanted} particles may be spawned this tick (thins evenly over the limit). */
    public int allowParticles(int wanted) {
        int left = maxParticlesPerTick - particlesThisTick;
        int n = Math.max(0, Math.min(wanted, left));
        particlesThisTick += n;
        return n;
    }

    /** Players who should see particles at {@code at}. */
    public List<Player> viewers(World w, Location at) {
        List<Player> out = new ArrayList<>();
        double d2 = viewDistance * viewDistance;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) <= d2) out.add(p);
        return out;
    }

    public void warnOnce(String key, String msg) {
        if (plugin != null && warned.add(key)) plugin.getLogger().warning("[EXea] " + msg);
    }
}
