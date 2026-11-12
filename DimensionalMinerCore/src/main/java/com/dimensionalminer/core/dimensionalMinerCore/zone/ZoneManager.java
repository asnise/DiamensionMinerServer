package com.dimensionalminer.core.dimensionalMinerCore.zone;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.build.BuildManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Zones of the hub map (1.5), mirrored in every channel world (they share the map).
 * SAFE: no hostile spawns, mobs drop players inside as targets, players and mobs can't hurt each
 * other (also across the border), no PvP. PVP: players may fight when both are inside. Elsewhere
 * PvP follows combat.pvp (off). A SAFE zone wins where zones overlap. Block edits stay blocked on
 * the whole map (MapProtectionListener).
 * Shapes: BOX (from the /dmb selection), CIRCLE (center + radius), POLY (points placed one by one).
 * Zones cover every height unless made with "3d" (BOX only). Stored in plugins/DimensionalMinerCore/zones.yml.
 */
public final class ZoneManager implements Listener, TabExecutor {

    public enum Type { SAFE, PVP }

    public enum Shape { BOX, CIRCLE, POLY }

    /**
     * Bounds (min/max) are the box itself for BOX and the bounding box for CIRCLE / POLY.
     * points = polygon corners as {x, z} (block centers) for POLY; cx/cz/r for CIRCLE.
     */
    public record Zone(String name, Type type, Shape shape, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       boolean fullHeight, List<double[]> points, double cx, double cz, double r) {

        boolean contains(Location l) {
            int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
            if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) return false;
            if (!fullHeight && (by < minY || by > maxY)) return false;
            double x = bx + 0.5, z = bz + 0.5;
            return switch (shape) {
                case BOX -> true;
                case CIRCLE -> (x - cx) * (x - cx) + (z - cz) * (z - cz) <= r * r;
                case POLY -> insidePolygon(points, x, z);
            };
        }

        String describe() {
            String h = fullHeight ? "every height" : "Y " + minY + ".." + maxY;
            return switch (shape) {
                case BOX -> "box X " + minX + ".." + maxX + ", Z " + minZ + ".." + maxZ + ", " + h;
                case CIRCLE -> String.format(Locale.ROOT, "circle center %.0f %.0f, radius %.0f, %s", cx - 0.5, cz - 0.5, r, h);
                case POLY -> "polygon " + points.size() + " points, X " + minX + ".." + maxX + ", Z " + minZ + ".." + maxZ + ", " + h;
            };
        }
    }

    /** Even-odd ray casting on the X/Z plane. */
    static boolean insidePolygon(List<double[]> pts, double x, double z) {
        boolean in = false;
        for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
            double xi = pts.get(i)[0], zi = pts.get(i)[1], xj = pts.get(j)[0], zj = pts.get(j)[1];
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    private final DimensionalMinerCore plugin;
    private final BuildManager build;
    private final File file;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Type> lastZone = new HashMap<>();
    /** Players who turned zone outlines on with /zone show. */
    private final Set<UUID> showing = new HashSet<>();
    /** Polygon corners being placed with /zone point, per admin. */
    private final Map<UUID, List<double[]>> drafts = new HashMap<>();
    private final NamespacedKey wandKey;

    public ZoneManager(DimensionalMinerCore plugin, BuildManager build) {
        this.plugin = plugin;
        this.build = build;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        this.wandKey = new NamespacedKey(plugin, "dmb_wand"); // same key as BuildCommand's wand
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { dropTargets(); drawOutlines(); }, 20L, 10L);
    }

    // ------------------------------------------------------------------ queries

    private boolean mapWorld(Location l) {
        if (l == null || l.getWorld() == null) return false;
        List<String> worlds = plugin.getChannelManager() != null ? plugin.getChannelManager().getChannelWorldNames() : List.of("overworld_t0");
        return worlds.contains(l.getWorld().getName());
    }

    /** The zone type at a location (SAFE wins over PVP), or null. */
    public Type at(Location l) {
        if (!mapWorld(l)) return null;
        Type found = null;
        for (Zone z : zones.values()) {
            if (!z.contains(l)) continue;
            if (z.type() == Type.SAFE) return Type.SAFE;
            found = z.type();
        }
        return found;
    }

    private List<Zone> zonesAt(Location l) {
        if (!mapWorld(l)) return List.of();
        return zones.values().stream().filter(z -> z.contains(l)).toList();
    }

    public boolean isSafe(Location l) { return at(l) == Type.SAFE; }

    public boolean isPvp(Location l) { return at(l) == Type.PVP; }

    // ------------------------------------------------------------------ rules

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason r = event.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND) return; // plugins / admins
        if (event.getEntity() instanceof Enemy && isSafe(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && event.getEntity() instanceof Enemy && isSafe(p.getLocation())) event.setCancelled(true);
    }

    /** A mob that was already chasing a player keeps its target; drop it once the player is in a Safe Zone. */
    private void dropTargets() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isSafe(p.getLocation())) continue;
            for (Entity e : p.getNearbyEntities(32, 16, 32)) {
                if (e instanceof Mob m && e instanceof Enemy && p.equals(m.getTarget())) m.setTarget(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ() && event.getFrom().getWorld() == event.getTo().getWorld()) return;
        Player p = event.getPlayer();
        Type now = at(event.getTo());
        Type before = lastZone.get(p.getUniqueId());
        if (now == before) return;
        if (now == null) lastZone.remove(p.getUniqueId()); else lastZone.put(p.getUniqueId(), now);
        if (now == Type.SAFE) p.sendActionBar(Component.text("Entering a Safe Zone: no combat here", NamedTextColor.GREEN));
        else if (now == Type.PVP) p.sendActionBar(Component.text("Entering a PvP Zone: other players can attack you", NamedTextColor.RED));
        else p.sendActionBar(Component.text(before == Type.SAFE ? "Leaving the Safe Zone" : "Leaving the PvP Zone", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastZone.remove(id);
        showing.remove(id);
        drafts.remove(id);
    }

    // ------------------------------------------------------------------ debug outlines

    private static final Color SEL = Color.fromRGB(255, 255, 255), POS1 = Color.fromRGB(80, 255, 80), POS2 = Color.fromRGB(255, 80, 80),
            SAFE_C = Color.fromRGB(80, 230, 120), PVP_C = Color.fromRGB(255, 60, 60), DRAFT = Color.fromRGB(255, 220, 60);
    private static final double VIEW = 48;
    private int budget;

    private boolean holdingWand(Player p) {
        ItemStack i = p.getInventory().getItemInMainHand();
        return i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /**
     * Every 10 ticks, for admins holding the /dmb wand, with /zone show on, or placing polygon points:
     * their /dmb selection (white, pos1 green, pos2 red) + an action bar "from → to, size", the polygon
     * draft (yellow), and the zones around them (SAFE green, PVP red; full-height zones are drawn as a
     * band around the player's height). Particles are sent to that player only.
     */
    private void drawOutlines() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean wand = holdingWand(p) && p.hasPermission("dimensionalminer.admin");
            List<double[]> draft = drafts.get(p.getUniqueId());
            if (!wand && !showing.contains(p.getUniqueId()) && draft == null) continue;
            budget = 1200;
            double py = Math.floor(p.getLocation().getY());
            BuildManager.Selection sel = build.selection(p);
            boolean here = sel.world != null && sel.world.equals(p.getWorld().getName());
            if (here && sel.pos1 != null) marker(p, sel.pos1, POS1);
            if (here && sel.pos2 != null) marker(p, sel.pos2, POS2);
            if (here && sel.complete()) {
                int[] a = sel.min(), b = sel.max();
                box(p, a[0], a[1], a[2], b[0] + 1, b[1] + 1, b[2] + 1, SEL, 0.9f);
                if (wand) p.sendActionBar(Component.text("pos1 " + xyz(sel.pos1) + "  →  pos2 " + xyz(sel.pos2) + "   size "
                        + (b[0] - a[0] + 1) + "×" + (b[1] - a[1] + 1) + "×" + (b[2] - a[2] + 1) + " (X×Y×Z)", NamedTextColor.LIGHT_PURPLE));
            } else if (wand && draft == null) {
                p.sendActionBar(Component.text(sel.pos1 == null ? "Left-click a block = pos1" : "pos1 " + xyz(sel.pos1)
                        + "  ·  right-click a block = pos2", NamedTextColor.LIGHT_PURPLE));
            }
            if (draft != null && !draft.isEmpty()) {
                outline(p, draft, py, py + 2, DRAFT, 1.1f, false);
                p.sendActionBar(Component.text(draft.size() + " point(s) · /zone point to add · /zone poly <name> <safe|pvp> to finish",
                        NamedTextColor.YELLOW));
            }
            if (!mapWorld(p.getLocation())) continue;
            for (Zone z : zones.values()) {
                double y0 = z.fullHeight() ? py - 1 : z.minY(), y1 = z.fullHeight() ? py + 3 : z.maxY() + 1;
                Color c = z.type() == Type.SAFE ? SAFE_C : PVP_C;
                switch (z.shape()) {
                    case BOX -> box(p, z.minX(), y0, z.minZ(), z.maxX() + 1, y1, z.maxZ() + 1, c, 1.2f);
                    case POLY -> outline(p, z.points(), y0, y1, c, 1.2f, true);
                    case CIRCLE -> outline(p, circle(z.cx(), z.cz(), z.r()), y0, y1, c, 1.2f, true);
                }
            }
        }
    }

    private static List<double[]> circle(double cx, double cz, double r) {
        int n = Math.max(16, (int) Math.ceil(2 * Math.PI * r / 1.5));
        List<double[]> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pts.add(new double[]{cx + r * Math.cos(2 * Math.PI * i / n), cz + r * Math.sin(2 * Math.PI * i / n)});
        return pts;
    }

    private static String xyz(int[] v) {
        return v[0] + " " + v[1] + " " + v[2];
    }

    private void marker(Player p, int[] b, Color c) {
        box(p, b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1, c, 1.4f);
    }

    /** Closed (or open) outline of X/Z points at y0 and y1, with vertical posts at every point. */
    private void outline(Player p, List<double[]> pts, double y0, double y1, Color c, float size, boolean closed) {
        boolean posts = pts.size() <= 24;
        for (int i = 0; i < pts.size(); i++) {
            double[] a = pts.get(i);
            if (posts) line(p, a[0], y0, a[1], a[0], y1, a[1], c, size);
            if (i + 1 < pts.size() || closed && pts.size() > 2) {
                double[] b = pts.get((i + 1) % pts.size());
                line(p, a[0], y0, a[1], b[0], y0, b[1], c, size);
                line(p, a[0], y1, a[1], b[0], y1, b[1], c, size);
            }
        }
    }

    private void box(Player p, double x0, double y0, double z0, double x1, double y1, double z1, Color c, float size) {
        double[][] e = {
                {x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1}};
        for (double[] l : e) line(p, l[0], l[1], l[2], l[3], l[4], l[5], c, size);
    }

    private void line(Player p, double x0, double y0, double z0, double x1, double y1, double z1, Color c, float size) {
        Particle.DustOptions dust = new Particle.DustOptions(c, size);
        Location eye = p.getLocation();
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int steps = Math.max(1, (int) Math.ceil(len / 0.5));
        for (int i = 0; i <= steps && budget > 0; i++) {
            double t = (double) i / steps;
            double x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t, z = z0 + (z1 - z0) * t;
            if (Math.abs(x - eye.getX()) > VIEW || Math.abs(z - eye.getZ()) > VIEW || Math.abs(y - eye.getY()) > VIEW) continue;
            p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
            budget--;
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        zones.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("zones");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            ConfigurationSection z = s.getConfigurationSection(name);
            if (z == null) continue;
            try {
                Type type = Type.valueOf(z.getString("type", "SAFE").toUpperCase(Locale.ROOT));
                Shape shape = Shape.valueOf(z.getString("shape", "BOX").toUpperCase(Locale.ROOT));
                boolean full = z.getBoolean("full_height", true);
                Zone zone = switch (shape) {
                    case BOX -> {
                        List<Integer> min = z.getIntegerList("min"), max = z.getIntegerList("max");
                        yield new Zone(name, type, shape, min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2),
                                full, List.of(), 0, 0, 0);
                    }
                    case CIRCLE -> circleZone(name, type, z.getDouble("cx"), z.getDouble("cz"), z.getDouble("r"));
                    case POLY -> {
                        List<double[]> pts = new ArrayList<>();
                        for (String pt : z.getStringList("points")) {
                            String[] xz = pt.split(",");
                            pts.add(new double[]{Double.parseDouble(xz[0]), Double.parseDouble(xz[1])});
                        }
                        yield polyZone(name, type, pts);
                    }
                };
                zones.put(name, zone);
            } catch (Exception e) {
                plugin.getLogger().warning("[Zones] Bad zone '" + name + "' in zones.yml: " + e.getMessage());
            }
        }
        plugin.getLogger().info("[Zones] Loaded " + zones.size() + " zone(s)");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones.values()) {
            String k = "zones." + z.name();
            y.set(k + ".type", z.type().name());
            y.set(k + ".shape", z.shape().name());
            y.set(k + ".full_height", z.fullHeight());
            switch (z.shape()) {
                case BOX -> {
                    y.set(k + ".min", List.of(z.minX(), z.minY(), z.minZ()));
                    y.set(k + ".max", List.of(z.maxX(), z.maxY(), z.maxZ()));
                }
                case CIRCLE -> {
                    y.set(k + ".cx", z.cx());
                    y.set(k + ".cz", z.cz());
                    y.set(k + ".r", z.r());
                }
                case POLY -> y.set(k + ".points", z.points().stream().map(pt -> pt[0] + "," + pt[1]).toList());
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[Zones] Could not save zones.yml: " + e.getMessage());
        }
    }

    private static Zone circleZone(String name, Type type, double cx, double cz, double r) {
        return new Zone(name, type, Shape.CIRCLE, (int) Math.floor(cx - r), 0, (int) Math.floor(cz - r),
                (int) Math.floor(cx + r), 0, (int) Math.floor(cz + r), true, List.of(), cx, cz, r);
    }

    private static Zone polyZone(String name, Type type, List<double[]> pts) {
        double mnx = Double.MAX_VALUE, mnz = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mxz = -Double.MAX_VALUE;
        for (double[] pt : pts) { mnx = Math.min(mnx, pt[0]); mnz = Math.min(mnz, pt[1]); mxx = Math.max(mxx, pt[0]); mxz = Math.max(mxz, pt[1]); }
        return new Zone(name, type, Shape.POLY, (int) Math.floor(mnx), 0, (int) Math.floor(mnz), (int) Math.floor(mxx), 0,
                (int) Math.floor(mxz), true, List.copyOf(pts), 0, 0, 0);
    }

    // ------------------------------------------------------------------ /zone

    private Type type(Player p, String s) {
        try { return Type.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) {
            p.sendMessage(msg("Type must be safe or pvp.", NamedTextColor.RED));
            return null;
        }
    }

    private String name(Player p, String s) {
        String n = s.toLowerCase(Locale.ROOT);
        if (n.matches("[a-z0-9_]{1,32}")) return n;
        p.sendMessage(msg("Name: a-z, 0-9, _ (max 32).", NamedTextColor.RED));
        return null;
    }

    private void put(Player p, Zone z) {
        boolean replaced = zones.put(z.name(), z) != null;
        save();
        p.sendMessage(msg("Zone " + z.name() + " (" + z.type() + ") " + (replaced ? "replaced" : "created") + ": " + z.describe()
                + ", in every channel. /zone show to see it.", NamedTextColor.GREEN));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean onMap = mapWorld(p.getLocation());
        switch (sub) {
            case "create", "box" -> {
                if (args.length < 3) { p.sendMessage(msg("Usage: /zone create <name> <safe|pvp> [3d]  (box from the /dmb wand selection; without 3d it covers every height)", NamedTextColor.YELLOW)); return true; }
                String n = name(p, args[1]); Type t = type(p, args[2]);
                if (n == null || t == null) return true;
                BuildManager.Selection sel = build.selection(p);
                if (!sel.complete()) { p.sendMessage(msg("Select two corners first (/dmb wand, /dmb pos1, /dmb pos2).", NamedTextColor.RED)); return true; }
                if (!onMap) { p.sendMessage(msg("Zones work on the hub map (and its channels) only.", NamedTextColor.RED)); return true; }
                int[] mn = sel.min(), mx = sel.max();
                boolean full = !(args.length > 3 && args[3].equalsIgnoreCase("3d"));
                put(p, new Zone(n, t, Shape.BOX, mn[0], mn[1], mn[2], mx[0], mx[1], mx[2], full, List.of(), 0, 0, 0));
            }
            case "circle" -> {
                if (args.length < 4) { p.sendMessage(msg("Usage: /zone circle <name> <safe|pvp> <radius>  (centered where you stand)", NamedTextColor.YELLOW)); return true; }
                String n = name(p, args[1]); Type t = type(p, args[2]);
                if (n == null || t == null) return true;
                double r;
                try { r = Double.parseDouble(args[3]); } catch (NumberFormatException e) { r = -1; }
                if (r < 1 || r > 512) { p.sendMessage(msg("Radius must be 1-512.", NamedTextColor.RED)); return true; }
                if (!onMap) { p.sendMessage(msg("Zones work on the hub map (and its channels) only.", NamedTextColor.RED)); return true; }
                Location l = p.getLocation();
                put(p, circleZone(n, t, l.getBlockX() + 0.5, l.getBlockZ() + 0.5, r));
            }
            case "point" -> {
                String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "add";
                List<double[]> d = drafts.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
                switch (op) {
                    case "undo" -> { if (!d.isEmpty()) d.removeLast(); }
                    case "clear" -> { drafts.remove(p.getUniqueId()); p.sendMessage(msg("Polygon points cleared.", NamedTextColor.GRAY)); return true; }
                    default -> {
                        Location l = p.getLocation();
                        d.add(new double[]{l.getBlockX() + 0.5, l.getBlockZ() + 0.5});
                    }
                }
                p.sendMessage(msg("Polygon: " + d.size() + " point(s). Walk to the next corner and /zone point; /zone point undo; "
                        + "/zone poly <name> <safe|pvp> to finish (3+ points).", NamedTextColor.YELLOW));
            }
            case "poly" -> {
                if (args.length < 3) { p.sendMessage(msg("Usage: /zone poly <name> <safe|pvp>  (after placing corners with /zone point)", NamedTextColor.YELLOW)); return true; }
                String n = name(p, args[1]); Type t = type(p, args[2]);
                if (n == null || t == null) return true;
                List<double[]> d = drafts.get(p.getUniqueId());
                if (d == null || d.size() < 3) { p.sendMessage(msg("Place at least 3 corners with /zone point first.", NamedTextColor.RED)); return true; }
                if (!onMap) { p.sendMessage(msg("Zones work on the hub map (and its channels) only.", NamedTextColor.RED)); return true; }
                put(p, polyZone(n, t, d));
                drafts.remove(p.getUniqueId());
            }
            case "remove" -> {
                String n;
                if (args.length >= 2) n = args[1].toLowerCase(Locale.ROOT);
                else {
                    List<Zone> here = zonesAt(p.getLocation());
                    if (here.isEmpty()) { p.sendMessage(msg("You are not in a zone. Use /zone remove <name> (see /zone list).", NamedTextColor.RED)); return true; }
                    if (here.size() > 1) {
                        p.sendMessage(msg("Several zones here: " + String.join(", ", here.stream().map(Zone::name).toList())
                                + ". Use /zone remove <name>.", NamedTextColor.YELLOW));
                        return true;
                    }
                    n = here.getFirst().name();
                }
                if (zones.remove(n) == null) { p.sendMessage(msg("No zone called " + n + ".", NamedTextColor.RED)); return true; }
                save();
                p.sendMessage(msg("Zone " + n + " removed (every channel).", NamedTextColor.YELLOW));
            }
            case "list" -> {
                if (zones.isEmpty()) { p.sendMessage(msg("No zones.", NamedTextColor.GRAY)); return true; }
                for (Zone z : zones.values())
                    p.sendMessage(msg(z.name() + " " + z.type() + "  " + z.describe(), z.type() == Type.SAFE ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "show" -> {
                boolean on = showing.add(p.getUniqueId()) || !showing.remove(p.getUniqueId());
                p.sendMessage(msg(on ? "Zone outlines ON (SAFE green, PVP red; your /dmb selection white). /zone show again to hide."
                        : "Zone outlines OFF (still shown while you hold the /dmb wand).", NamedTextColor.GRAY));
            }
            case "here" -> {
                List<Zone> here = zonesAt(p.getLocation());
                p.sendMessage(msg(here.isEmpty() ? "No zone here (PvP " + (plugin.getCombatService() != null
                        && plugin.getCombatService().config().pvp() ? "on" : "off") + ")"
                        : "You are in: " + String.join(", ", here.stream().map(z -> z.name() + " (" + z.type() + ")").toList()), NamedTextColor.GRAY));
            }
            default -> p.sendMessage(msg("Usage: /zone <create|circle|point|poly|remove|list|here|show>", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> opts = switch (args.length) {
            case 1 -> List.of("create", "circle", "point", "poly", "remove", "list", "here", "show");
            case 2 -> sub.equals("remove") ? new ArrayList<>(zones.keySet()) : sub.equals("point") ? List.of("add", "undo", "clear") : List.of();
            case 3 -> List.of("create", "box", "circle", "poly").contains(sub) ? List.of("safe", "pvp") : List.of();
            case 4 -> sub.equals("create") || sub.equals("box") ? List.of("3d") : sub.equals("circle") ? List.of("8", "16", "32") : List.of();
            default -> List.of();
        };
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return opts.stream().filter(o -> o.startsWith(last)).toList();
    }

    private static Component msg(String s, NamedTextColor c) {
        return Component.text("[Zone] " + s, c);
    }
}
