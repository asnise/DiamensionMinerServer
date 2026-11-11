package com.dimensionalminer.core.dimensionalMinerCore.build;

import com.dimensionalminer.core.dimensionalMinerCore.ContentRegistryManager;
import com.dimensionalminer.core.dimensionalMinerCore.mining.MineNodeManager;
import com.dimensionalminer.core.dimensionalMinerCore.mining.OreType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A blueprint: a JSON list of build operations run relative to where an admin stands.
 * Written by hand or by an AI agent; see tools/blueprints/README.md for the format.
 *
 * <p>Relative coordinates are [forward, up, right] from the player's block position, rotated to the
 * cardinal direction the player faces, so a blueprint always builds "in front of" the player.
 * Operations run in order against the world plus earlier operations (a later ore_vein sees the
 * tunnels carved before it). Planning only reads the world; {@link BuildManager} applies the result.
 */
public final class Blueprint {

    private final String name;
    private final JsonObject json;

    public Blueprint(String name, JsonObject json) {
        this.name = name;
        this.json = json;
    }

    public String name() { return name; }

    public String description() {
        return json.has("description") ? json.get("description").getAsString() : "";
    }

    /** Orientation + origin of one run. */
    private record Frame(int ox, int oy, int oz, int fx, int fz) {
        int[] abs(int f, int u, int r) {
            // right vector = (-fz, fx): facing east (+x) -> right is south (+z)
            return new int[]{ox + f * fx + r * (-fz), oy + u, oz + f * fz + r * fx};
        }

        double[] dir(double f, double u, double r) {
            return new double[]{f * fx + r * (-fz), u, f * fz + r * fx};
        }
    }

    /** Working state of one planning pass. */
    private static final class Ctx {
        final World world;
        final Map<Long, BuildManager.Change> changes = new LinkedHashMap<>();
        final Map<String, List<MineNodeManager.Pos>> nodes = new LinkedHashMap<>();
        final Set<Long> nodeKeys = new HashSet<>();
        final Map<String, int[]> markers = new LinkedHashMap<>();
        final Random rnd;

        Ctx(World world, long seed) { this.world = world; this.rnd = new Random(seed); }

        Material typeAt(int x, int y, int z) {
            BuildManager.Change c = changes.get(BuildManager.key(x, y, z));
            if (c != null) return c.data().getMaterial();
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return Material.AIR;
            return world.getBlockAt(x, y, z).getType();
        }

        void set(int x, int y, int z, BlockData data) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return;
            if (nodeKeys.contains(BuildManager.key(x, y, z))) return; // a node placed earlier in this plan wins
            changes.put(BuildManager.key(x, y, z), new BuildManager.Change(x, y, z, data));
            if (changes.size() > BuildManager.MAX_BLOCKS) {
                throw new IllegalArgumentException("blueprint changes more than " + BuildManager.MAX_BLOCKS + " blocks");
            }
        }
    }

    /**
     * Plans the blueprint at the given origin, facing the given direction.
     * @throws IllegalArgumentException with a readable message for any invalid operation
     */
    public BuildManager.Plan plan(World world, int ox, int oy, int oz, BlockFace facing, ContentRegistryManager registry) {
        int fx = facing.getModX(), fz = facing.getModZ();
        if (Math.abs(fx) + Math.abs(fz) != 1) throw new IllegalArgumentException("facing must be a cardinal direction");
        Frame frame = new Frame(ox, oy, oz, fx, fz);
        long seed = json.has("seed") ? json.get("seed").getAsLong() : 1L;
        Ctx ctx = new Ctx(world, seed);

        if (!json.has("ops") || !json.get("ops").isJsonArray()) throw new IllegalArgumentException("'ops' array is required");
        int i = 0;
        for (JsonElement el : json.getAsJsonArray("ops")) {
            i++;
            if (!el.isJsonObject()) throw new IllegalArgumentException("op #" + i + " is not an object");
            JsonObject op = el.getAsJsonObject();
            String type = str(op, "op", null);
            try {
                switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
                    case "box" -> box(ctx, frame, op);
                    case "sphere" -> sphere(ctx, frame, op);
                    case "tunnel" -> tunnel(ctx, frame, op);
                    case "replace" -> replace(ctx, frame, op);
                    case "ore_vein" -> oreVein(ctx, frame, op, registry);
                    case "light" -> light(ctx, frame, op);
                    case "node" -> node(ctx, frame, op, registry);
                    case "marker" -> ctx.markers.put(str(op, "name", "marker" + i), at(frame, op, "at"));
                    default -> throw new IllegalArgumentException("unknown op '" + type + "'");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("op #" + i + " (" + type + "): " + e.getMessage());
            }
        }
        return new BuildManager.Plan("blueprint " + name, new ArrayList<>(ctx.changes.values()), ctx.nodes, ctx.markers);
    }

    // ---------- operations ----------

    /** {"op":"box","from":[f,u,r],"to":[f,u,r],"block":"stone","hollow":false} */
    private static void box(Ctx ctx, Frame fr, JsonObject op) {
        int[] a = at(fr, op, "from"), b = at(fr, op, "to");
        BlockData data = blockData(str(op, "block", "air"));
        boolean hollow = op.has("hollow") && op.get("hollow").getAsBoolean();
        int[] lo = min(a, b), hi = max(a, b);
        for (int x = lo[0]; x <= hi[0]; x++)
            for (int y = lo[1]; y <= hi[1]; y++)
                for (int z = lo[2]; z <= hi[2]; z++) {
                    boolean shell = x == lo[0] || x == hi[0] || y == lo[1] || y == hi[1] || z == lo[2] || z == hi[2];
                    if (!hollow || shell) ctx.set(x, y, z, data);
                }
    }

    /** {"op":"sphere","center":[f,u,r],"radius":6,"block":"air"} (optional "radii":[f,u,r] for an ellipsoid) */
    private static void sphere(Ctx ctx, Frame fr, JsonObject op) {
        int[] c = at(fr, op, "center");
        double rf, ru, rr;
        if (op.has("radii")) {
            JsonArray r = op.getAsJsonArray("radii");
            rf = r.get(0).getAsDouble(); ru = r.get(1).getAsDouble(); rr = r.get(2).getAsDouble();
        } else {
            rf = ru = rr = num(op, "radius", 4);
        }
        // radii are in the blueprint frame; forward/right swap axes depending on facing
        double rx = fr.fx() != 0 ? rf : rr, rz = fr.fx() != 0 ? rr : rf;
        carveEllipsoid(ctx, c[0], c[1], c[2], rx, ru, rz, blockData(str(op, "block", "air")));
    }

    /**
     * {"op":"tunnel","from":[f,u,r],"direction":[f,u,r],"length":40,"radius":2.5,"wander":0.25,"block":"air"}
     * A worm: steps one block at a time, carving a sphere each step, gently turning by "wander".
     */
    private static void tunnel(Ctx ctx, Frame fr, JsonObject op) {
        int[] start = at(fr, op, "from");
        JsonArray d = op.has("direction") ? op.getAsJsonArray("direction") : null;
        double[] dir = fr.dir(d == null ? 1 : d.get(0).getAsDouble(), d == null ? 0 : d.get(1).getAsDouble(), d == null ? 0 : d.get(2).getAsDouble());
        normalize(dir);
        int length = (int) num(op, "length", 20);
        double radius = num(op, "radius", 2);
        double wander = num(op, "wander", 0.2);
        BlockData data = blockData(str(op, "block", "air"));
        if (length < 1 || length > 2000) throw new IllegalArgumentException("length must be 1..2000");
        double x = start[0] + 0.5, y = start[1] + 0.5, z = start[2] + 0.5;
        for (int step = 0; step < length; step++) {
            carveEllipsoid(ctx, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), radius, radius * 0.85, radius, data);
            dir[0] += (ctx.rnd.nextDouble() - 0.5) * wander;
            dir[1] += (ctx.rnd.nextDouble() - 0.5) * wander * 0.35; // stay mostly level
            dir[2] += (ctx.rnd.nextDouble() - 0.5) * wander;
            normalize(dir);
            x += dir[0]; y += dir[1]; z += dir[2];
        }
    }

    /** {"op":"replace","from":[...],"to":[...],"match":["stone","dirt"],"block":"deepslate"} */
    private static void replace(Ctx ctx, Frame fr, JsonObject op) {
        int[] a = at(fr, op, "from"), b = at(fr, op, "to");
        Set<Material> match = materials(op, "match", null);
        BlockData data = blockData(str(op, "block", "air"));
        int[] lo = min(a, b), hi = max(a, b);
        for (int x = lo[0]; x <= hi[0]; x++)
            for (int y = lo[1]; y <= hi[1]; y++)
                for (int z = lo[2]; z <= hi[2]; z++)
                    if (match.contains(ctx.typeAt(x, y, z))) ctx.set(x, y, z, data);
    }

    /**
     * {"op":"ore_vein","from":[...],"to":[...],"ore":"ore_prim_metal","chance":0.06,"max":30,
     *  "replace":["stone","deepslate"],"exposed_only":true}
     * Turns some solid blocks in the region into mining nodes. exposed_only = must touch air
     * (walls/floor/ceiling of carved space), so every node can actually be reached.
     */
    private static void oreVein(Ctx ctx, Frame fr, JsonObject op, ContentRegistryManager registry) {
        int[] a = at(fr, op, "from"), b = at(fr, op, "to");
        OreType ore = oreType(op, registry);
        double chance = num(op, "chance", 0.05);
        int max = (int) num(op, "max", Integer.MAX_VALUE);
        boolean exposedOnly = !op.has("exposed_only") || op.get("exposed_only").getAsBoolean();
        Set<Material> replaceable = materials(op, "replace", Set.of(Material.STONE, Material.DEEPSLATE, Material.ANDESITE,
                Material.DIORITE, Material.GRANITE, Material.TUFF, Material.COBBLESTONE));
        BlockData oreBlock = ore.block().createBlockData();
        int[] lo = min(a, b), hi = max(a, b);
        int placed = 0;
        for (int x = lo[0]; x <= hi[0] && placed < max; x++)
            for (int y = lo[1]; y <= hi[1] && placed < max; y++)
                for (int z = lo[2]; z <= hi[2] && placed < max; z++) {
                    if (!replaceable.contains(ctx.typeAt(x, y, z))) continue;
                    if (exposedOnly && !touchesAir(ctx, x, y, z)) continue;
                    if (ctx.rnd.nextDouble() >= chance) continue;
                    addNode(ctx, x, y, z, ore, oreBlock);
                    placed++;
                }
    }

    /** {"op":"node","at":[f,u,r],"ore":"ore_prim_metal"} */
    private static void node(Ctx ctx, Frame fr, JsonObject op, ContentRegistryManager registry) {
        int[] p = at(fr, op, "at");
        OreType ore = oreType(op, registry);
        addNode(ctx, p[0], p[1], p[2], ore, ore.block().createBlockData());
    }

    /**
     * {"op":"light","from":[...],"to":[...],"block":"lantern","every":6}
     * Places the block on open floor (air with solid ground below) on a grid every N blocks.
     */
    private static void light(Ctx ctx, Frame fr, JsonObject op) {
        int[] a = at(fr, op, "from"), b = at(fr, op, "to");
        BlockData data = blockData(str(op, "block", "lantern"));
        int every = Math.max(2, (int) num(op, "every", 6));
        int[] lo = min(a, b), hi = max(a, b);
        for (int x = lo[0]; x <= hi[0]; x++)
            for (int z = lo[2]; z <= hi[2]; z++) {
                if (Math.floorMod(x, every) != 0 || Math.floorMod(z, every) != 0) continue;
                for (int y = lo[1]; y <= hi[1]; y++) {
                    if (ctx.typeAt(x, y, z).isAir() && ctx.typeAt(x, y - 1, z).isSolid()) {
                        ctx.set(x, y, z, data);
                        break; // lowest open floor in this column
                    }
                }
            }
    }

    // ---------- helpers ----------

    private static void addNode(Ctx ctx, int x, int y, int z, OreType ore, BlockData oreBlock) {
        ctx.set(x, y, z, oreBlock);
        long k = BuildManager.key(x, y, z);
        if (ctx.nodeKeys.add(k)) {
            ctx.nodes.computeIfAbsent(ore.id(), o -> new ArrayList<>()).add(new MineNodeManager.Pos(x, y, z));
        }
    }

    private static void carveEllipsoid(Ctx ctx, int cx, int cy, int cz, double rx, double ry, double rz, BlockData data) {
        int ix = (int) Math.ceil(rx), iy = (int) Math.ceil(ry), iz = (int) Math.ceil(rz);
        for (int dx = -ix; dx <= ix; dx++)
            for (int dy = -iy; dy <= iy; dy++)
                for (int dz = -iz; dz <= iz; dz++) {
                    double v = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) + (dz * dz) / (rz * rz);
                    if (v <= 1.0) ctx.set(cx + dx, cy + dy, cz + dz, data);
                }
    }

    private static boolean touchesAir(Ctx ctx, int x, int y, int z) {
        return ctx.typeAt(x + 1, y, z).isAir() || ctx.typeAt(x - 1, y, z).isAir()
                || ctx.typeAt(x, y + 1, z).isAir() || ctx.typeAt(x, y - 1, z).isAir()
                || ctx.typeAt(x, y, z + 1).isAir() || ctx.typeAt(x, y, z - 1).isAir();
    }

    private static OreType oreType(JsonObject op, ContentRegistryManager registry) {
        String id = str(op, "ore", null);
        OreType ore = registry.getOreType(id);
        if (ore == null) throw new IllegalArgumentException("unknown ore type '" + id + "' (see /mine types)");
        return ore;
    }

    private static int[] at(Frame fr, JsonObject op, String key) {
        if (!op.has(key) || !op.get(key).isJsonArray() || op.getAsJsonArray(key).size() != 3) {
            throw new IllegalArgumentException("'" + key + "' must be [forward, up, right]");
        }
        JsonArray a = op.getAsJsonArray(key);
        return fr.abs(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static BlockData blockData(String spec) {
        try {
            return Bukkit.createBlockData(spec.contains(":") ? spec : "minecraft:" + spec.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown block '" + spec + "'");
        }
    }

    private static Set<Material> materials(JsonObject op, String key, Set<Material> fallback) {
        if (!op.has(key)) {
            if (fallback == null) throw new IllegalArgumentException("'" + key + "' is required");
            return fallback;
        }
        Set<Material> out = new HashSet<>();
        for (JsonElement e : op.getAsJsonArray(key)) {
            Material m = Material.matchMaterial(e.getAsString().toUpperCase(Locale.ROOT));
            if (m == null) throw new IllegalArgumentException("unknown block '" + e.getAsString() + "' in '" + key + "'");
            out.add(m);
        }
        return out;
    }

    private static String str(JsonObject o, String k, String d) { return o.has(k) ? o.get(k).getAsString() : d; }

    private static double num(JsonObject o, String k, double d) { return o.has(k) ? o.get(k).getAsDouble() : d; }

    private static int[] min(int[] a, int[] b) { return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])}; }

    private static int[] max(int[] a, int[] b) { return new int[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])}; }

    private static void normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-6) { v[0] = 1; v[1] = 0; v[2] = 0; return; }
        v[0] /= len; v[1] /= len; v[2] /= len;
    }
}
