package com.dimensionalminer.core.dimensionalMinerCore.channel;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.util.WorldFiles;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Channels of every Tier map (1.1 / 1.3). Each Tier is one map ("family") with channels 1..N:
 * <ul>
 *   <li>Tier 0 = the hub: channel 1 is the hub world {@code overworld_t0} itself, channels 2..N are
 *       copies of its overworld dimension ({@code overworld_t0_chN}).</li>
 *   <li>Tier k ≥ 1 (config {@code tiers.k.map}): channel 1 is {@code overworld_tk}, copied once from the map
 *       folder into the hub level's dimensions; channels 2..N are copies of that ({@code overworld_tk_chN}).</li>
 * </ul>
 * 1.3: {@code channels.per_tier} channels are always open; a channel holds at most {@code channels.max_players}
 * (admins may exceed it). Joining the game or warping into a tier lands in the party's channel if it has room,
 * else the least busy one. When every open channel is full a new one opens (up to {@code channels.max_per_tier});
 * such extra channels close after {@code channels.extra_idle_minutes} empty (their folder is kept for reuse).
 * Copies run on an async thread, worlds load on the main thread; everything is stateless (autoSave off).
 */
public class ChannelManager implements Listener {

    public static final String HUB_WORLD = "overworld_t0";
    /** Written into a copied folder once the copy finished, so a crash mid-copy is redone next start. */
    private static final String CLONE_MARKER = ".dm_channel_clone";

    private final DimensionalMinerCore plugin;
    /** tier → channel number → world name (open or opening channels only). */
    private final Map<Integer, Map<Integer, String>> families = new TreeMap<>();
    private final Map<String, Integer> tierOfWorld = new ConcurrentHashMap<>();
    /** tier → folder that new channels of that tier are copied from. */
    private final Map<Integer, File> templates = new ConcurrentHashMap<>();
    private final Set<String> opening = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();
    private final int minChannels, maxChannels, maxPlayers;
    private final long extraIdleMs;

    public ChannelManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        var c = plugin.getConfig();
        this.minChannels = Math.clamp(c.getInt("channels.per_tier", 3), 1, 9);
        this.maxChannels = Math.clamp(c.getInt("channels.max_per_tier", 9), minChannels, 9);
        this.maxPlayers = Math.max(1, c.getInt("channels.max_players", 50));
        this.extraIdleMs = Math.max(1, c.getLong("channels.extra_idle_minutes", 10)) * 60_000L;
        initChannels();
        Bukkit.getScheduler().runTaskTimer(plugin, this::closeIdleExtras, 20L * 30, 20L * 30);
    }

    private File dimensions() {
        return new File(new File(Bukkit.getWorldContainer(), HUB_WORLD), "dimensions/minecraft");
    }

    public static String tierWorld(int tier) {
        return "overworld_t" + tier;
    }

    private static String baseOf(int tier) {
        return tier == 0 ? HUB_WORLD : tierWorld(tier);
    }

    private static String channelWorld(int tier, int ch) {
        return ch == 1 ? baseOf(tier) : baseOf(tier) + "_ch" + ch;
    }

    public void initChannels() {
        // Tier 0 = hub
        setupFamily(0, new File(dimensions(), "overworld"));
        // Tiers from config
        var tiers = plugin.getConfig().getConfigurationSection("tiers");
        if (tiers != null) {
            for (String key : tiers.getKeys(false)) {
                int tier;
                try { tier = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
                if (tier <= 0) continue;
                String map = tiers.getString(key + ".map", "");
                File source = map.isBlank() ? null : resolveMap(new File(Bukkit.getWorldContainer(), map));
                if (source == null && !new File(new File(dimensions(), tierWorld(tier)), CLONE_MARKER).isFile()) {
                    plugin.getLogger().warning("[Tier] Tier " + tier + ": map '" + map + "' not found (needs a region/ folder, "
                            + "or a level folder with dimensions/minecraft/overworld). A new world will be generated until the map is placed.");
                }
                setupTierBase(tier, source);
            }
        }
    }

    /** A dimension folder (has region/) or a level folder whose overworld dimension we take. */
    private static File resolveMap(File f) {
        if (new File(f, "region").isDirectory()) return f;
        File dim = new File(f, "dimensions/minecraft/overworld");
        if (new File(dim, "region").isDirectory()) return dim;
        if (f.isDirectory()) {
            File[] kids = f.listFiles(File::isDirectory);
            if (kids != null) for (File k : kids) {
                File r = resolveMap(k);
                if (r != null) return r;
            }
        }
        return null;
    }

    private Map<Integer, String> family(int tier) {
        return families.computeIfAbsent(tier, k -> new ConcurrentSkipListMap<>());
    }

    /** Tier k: copy the map into overworld_tk once, load it, then set up its channel copies. */
    private void setupTierBase(int tier, File source) {
        String base = tierWorld(tier);
        family(tier).put(1, base);
        tierOfWorld.put(base, tier);
        File target = new File(dimensions(), base);
        boolean needsCopy = source != null && !new File(target, CLONE_MARKER).isFile();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (needsCopy && Bukkit.getWorld(base) == null) {
                try {
                    WorldFiles.deleteTree(target.toPath());
                    WorldFiles.copyWorld(source.toPath(), target.toPath());
                    Files.writeString(new File(target, CLONE_MARKER).toPath(), "copied from " + source);
                    plugin.getLogger().info("[Tier] Copied map for tier " + tier + " from " + source);
                } catch (IOException e) {
                    plugin.getLogger().severe("[Tier] Failed to copy the map of tier " + tier + ": " + e.getMessage());
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                loadWorld(base, null);
                setupFamily(tier, target);
            });
        });
    }

    /** The always-open channels 2..per_tier of a tier as copies of its base dimension. */
    private void setupFamily(int tier, File template) {
        templates.put(tier, template);
        String base = baseOf(tier);
        family(tier).put(1, base);
        tierOfWorld.put(base, tier);
        for (int i = 2; i <= minChannels; i++) openChannel(tier, i, null);
    }

    /**
     * Copies (if needed) and loads channel {@code ch} of a tier. {@code onLoaded} runs on the main thread
     * once its world is loaded (dynamic channels use it to copy the mining nodes).
     */
    private void openChannel(int tier, int ch, Runnable onLoaded) {
        String wName = channelWorld(tier, ch);
        family(tier).put(ch, wName);
        tierOfWorld.put(wName, tier);
        if (Bukkit.getWorld(wName) != null) { // already loaded (e.g. after /reload): never touch a loaded world's files
            if (onLoaded != null) onLoaded.run();
            return;
        }
        File template = templates.get(tier);
        File target = new File(dimensions(), wName);
        boolean needsClone = !new File(target, CLONE_MARKER).isFile();
        if (needsClone && (template == null || !template.isDirectory())) {
            plugin.getLogger().severe("Channel template " + template + " not found; channel " + ch + " of tier " + tier + " is disabled.");
            family(tier).remove(ch);
            return;
        }
        if (!opening.add(wName)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (needsClone) {
                try {
                    WorldFiles.deleteTree(target.toPath());
                    WorldFiles.copyWorld(template.toPath(), target.toPath());
                    Files.writeString(new File(target, CLONE_MARKER).toPath(), "cloned from " + template.getName());
                    plugin.getLogger().info("Cloned map template for channel: " + wName);
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to clone map template for channel " + wName + ": " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> { opening.remove(wName); family(tier).remove(ch); });
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                opening.remove(wName);
                loadWorld(wName, baseOf(tier));
                if (Bukkit.getWorld(wName) == null) { family(tier).remove(ch); return; }
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /** Loads a stateless world; spawn copied from spawnFrom (its channel 1) when given. */
    private void loadWorld(String wName, String spawnFrom) {
        if (Bukkit.getWorld(wName) != null) return;
        World mainWorld = Bukkit.getWorld(HUB_WORLD);
        try {
            WorldCreator wc = new WorldCreator(wName);
            if (mainWorld != null) wc.copy(mainWorld);
            World w = wc.createWorld();
            if (w == null) {
                plugin.getLogger().severe("WorldCreator returned null for world: " + wName);
                return;
            }
            w.setAutoSave(false);
            w.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
            World src = spawnFrom != null ? Bukkit.getWorld(spawnFrom) : null;
            if (src != null) w.setSpawnLocation(src.getSpawnLocation());
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not load world: " + wName, e);
        }
    }

    // ------------------------------------------------------------------ 1.3: capacity, placement, extra channels

    public int maxPlayers() { return maxPlayers; }

    private static int load(String world) {
        World w = world == null ? null : Bukkit.getWorld(world);
        return w == null ? Integer.MAX_VALUE : w.getPlayerCount();
    }

    /**
     * Where a player should land in a tier (join, warp): a party member's channel if it has room, else the
     * least busy open channel (lowest number on a tie). Opens an extra channel when all are full.
     * Returns the world name, or null if nothing of that tier is loaded.
     */
    public String pick(Player p, int tier) {
        Map<Integer, String> fam = families.get(tier);
        if (fam == null || fam.isEmpty()) return null;
        String self = p.getWorld().getName();
        // 1. a party member's channel
        var party = plugin.getPartyManager() != null ? plugin.getPartyManager().getPartyByPlayer(p.getUniqueId()).orElse(null) : null;
        if (party != null) {
            for (UUID m : party.getMembers()) {
                if (m.equals(p.getUniqueId())) continue;
                Player mp = Bukkit.getPlayer(m);
                if (mp == null) continue;
                String w = mp.getWorld().getName();
                if (tierOf(w) == tier && Bukkit.getWorld(w) != null) {
                    int n = load(w) - (w.equals(self) ? 1 : 0);
                    if (n < maxPlayers) return w;
                }
            }
        }
        // 2. least busy (not counting the player if already there)
        String best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (String w : fam.values()) {
            if (Bukkit.getWorld(w) == null) continue;
            int n = load(w) - (w.equals(self) ? 1 : 0);
            if (n < bestLoad) { bestLoad = n; best = w; }
        }
        if (best != null && bestLoad >= maxPlayers) openExtra(tier); // everyone full: open another for the next ones
        return best;
    }

    /** Opens the next channel number of a tier if under max_per_tier and none is already opening. */
    private void openExtra(int tier) {
        Map<Integer, String> fam = family(tier);
        if (fam.size() >= maxChannels) return;
        for (String w : fam.values()) if (opening.contains(w)) return;
        int ch = 1;
        while (fam.containsKey(ch)) ch++;
        if (ch > maxChannels) return;
        int fch = ch;
        String base = baseOf(tier);
        plugin.getLogger().info("[Channel] All channels of tier " + tier + " are full: opening channel " + ch + ".");
        openChannel(tier, ch, () -> {
            var mines = plugin.getMineNodes();
            if (mines != null) mines.cloneWorldNodes(base, channelWorld(tier, fch)); // the ore nodes of channel 1
            Bukkit.broadcast(net.kyori.adventure.text.Component.text("[Channel] Channel " + fch + (tier > 0 ? " of Tier " + tier : "")
                    + " is now open.", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        });
    }

    /** Extra channels (above per_tier) close after extra_idle_minutes with nobody inside. */
    private void closeIdleExtras() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Map<Integer, String>> f : families.entrySet()) {
            for (Map.Entry<Integer, String> e : List.copyOf(f.getValue().entrySet())) {
                if (e.getKey() <= minChannels) continue;
                World w = Bukkit.getWorld(e.getValue());
                if (w == null) continue;
                if (w.getPlayerCount() > 0) { emptySince.remove(e.getValue()); continue; }
                long since = emptySince.computeIfAbsent(e.getValue(), k -> now);
                if (now - since < extraIdleMs) continue;
                if (Bukkit.unloadWorld(w, false)) {
                    f.getValue().remove(e.getKey());
                    emptySince.remove(e.getValue());
                    plugin.getLogger().info("[Channel] Closed idle extra channel " + e.getValue() + ".");
                }
            }
        }
    }

    /** Join: move to the party's / least busy channel of the same tier (after other join handlers ran). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (plugin.getDungeonInstanceManager() != null && plugin.getDungeonInstanceManager().isInDungeon(p)) return;
            String here = p.getWorld().getName();
            int tier = tierOf(here);
            if (tier < 0) return;
            String best = pick(p, tier);
            if (best == null || best.equals(here)) return;
            World target = Bukkit.getWorld(best);
            if (target == null) return;
            Location l = p.getLocation();
            p.teleportAsync(new Location(target, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch())).thenAccept(ok -> {
                if (ok) p.sendMessage(ChatColor.GRAY + "[Channel] You are in Channel " + getPlayerChannel(p)
                        + (tier > 0 ? " of Tier " + tier : "") + " (" + target.getPlayerCount() + "/" + maxPlayers + ").");
            });
        }, 10L);
    }

    // ------------------------------------------------------------------ queries

    /** Tier of a world (hub family = 0), or -1 if it is not a channel world. */
    public int tierOf(String world) {
        return world == null ? -1 : tierOfWorld.getOrDefault(world, -1);
    }

    /** All channel worlds of the map that {@code world} belongs to (just itself if none). */
    public List<String> familyOf(String world) {
        int tier = tierOf(world);
        return tier < 0 ? List.of(world) : familyWorlds(tier);
    }

    /** Open channel worlds of a tier, in channel order. */
    public List<String> familyWorlds(int tier) {
        Map<Integer, String> fam = families.get(tier);
        return fam == null ? new ArrayList<>() : new ArrayList<>(fam.values());
    }

    /** The world of a tier's channel, or null. */
    public String worldOf(int tier, int channel) {
        Map<Integer, String> fam = families.get(tier);
        return fam == null ? null : fam.get(channel);
    }

    public List<Integer> tiers() {
        return new ArrayList<>(families.keySet());
    }

    public int channelCount(int tier) {
        Map<Integer, String> fam = families.get(tier);
        return fam == null ? 0 : fam.size();
    }

    /** Channel number of the player inside their tier's family, or -1. */
    public int getPlayerChannel(Player player) {
        if (player == null) return -1;
        String wName = player.getWorld().getName();
        int tier = tierOf(wName);
        if (tier < 0) return -1;
        for (Map.Entry<Integer, String> e : families.get(tier).entrySet()) {
            if (e.getValue().equalsIgnoreCase(wName)) return e.getKey();
        }
        return -1;
    }

    /** Switches channel inside the player's current tier (outside a tier map: inside the hub). */
    public boolean switchChannel(Player player, int channelId) {
        int tier = Math.max(0, tierOf(player.getWorld().getName()));
        String targetWorldName = worldOf(tier, channelId);
        if (targetWorldName == null) {
            player.sendMessage(ChatColor.RED + "Channel " + channelId + " is not open (open: " + familyChannels(tier) + ").");
            return false;
        }
        if (plugin.getDungeonInstanceManager() != null && plugin.getDungeonInstanceManager().isInDungeon(player)) {
            player.sendMessage(ChatColor.RED + "You cannot switch channels while inside a dungeon! Please use /exitdungeon first.");
            return false;
        }
        int currentCh = getPlayerChannel(player);
        if (currentCh == channelId) {
            player.sendMessage(ChatColor.YELLOW + "You are already in Channel " + channelId + ".");
            return false;
        }
        World targetWorld = Bukkit.getWorld(targetWorldName);
        if (targetWorld == null) {
            player.sendMessage(ChatColor.RED + "[Channel] Channel " + channelId + " is still opening. Try again shortly.");
            return false;
        }
        if (targetWorld.getPlayerCount() >= maxPlayers && !player.isOp()) {
            player.sendMessage(ChatColor.RED + "[Channel] Channel " + channelId + " is full (" + maxPlayers + "/" + maxPlayers + ").");
            pick(player, tier); // opens another channel if every one is full
            return false;
        }

        plugin.getInventorySync().snapshotAndMaybeSave(player);
        Location loc = player.getLocation();
        Location targetLoc = currentCh != -1
                ? new Location(targetWorld, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch())
                : targetWorld.getSpawnLocation();
        player.teleportAsync(targetLoc).thenAccept(success -> {
            if (success) {
                player.sendMessage(ChatColor.GREEN + "[Channel] Switched to Channel " + channelId + (tier > 0 ? " of Tier " + tier : "") + "!");
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
            } else {
                player.sendMessage(ChatColor.RED + "[Channel] Teleportation failed.");
            }
        });
        return true;
    }

    private String familyChannels(int tier) {
        Map<Integer, String> fam = families.get(tier);
        return fam == null ? "none" : fam.keySet().toString();
    }

    /** Channel list of the player's tier (hub when outside every tier map). */
    public List<ChannelStatus> getChannels(Player player) {
        int tier = Math.max(0, tierOf(player.getWorld().getName()));
        List<ChannelStatus> list = new ArrayList<>();
        Map<Integer, String> fam = families.get(tier);
        if (fam == null) return list;
        for (Map.Entry<Integer, String> e : fam.entrySet()) {
            World w = Bukkit.getWorld(e.getValue());
            list.add(new ChannelStatus(e.getKey(), (tier > 0 ? "T" + tier + " " : "") + "CH-" + e.getKey(),
                    w != null ? w.getPlayerCount() : 0, maxPlayers, w != null));
        }
        return list;
    }

    /** Number of open channels of the player's tier. */
    public int getTotalChannels() { return minChannels; }

    /** World names of all channels of the hub map (channel 1 = hub world), in channel order. */
    public List<String> getChannelWorldNames() {
        return familyWorlds(0);
    }

    public record ChannelStatus(int id, String name, int onlineCount, int maxCount, boolean loaded) {}
}
