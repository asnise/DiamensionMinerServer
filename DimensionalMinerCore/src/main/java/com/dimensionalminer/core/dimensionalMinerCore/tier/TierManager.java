package com.dimensionalminer.core.dimensionalMinerCore.tier;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.channel.ChannelManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tier unlocks (1.2): Tier k needs {@code tiers.k.server_level} AND every flag in {@code tiers.k.flags}.
 * Flags live in {@code player_unlocks}: a dungeon clear grants {@code clear:<dungeon_id>} (in its reward
 * transaction, the boss proof), and the main-quest flags ({@code quest:<id>}) are granted by admins with
 * {@code /tier grant} until the quest system exists. Access is checked again on every teleport into a
 * tier world and on join (GDD: backend re-check). Travel is {@code /tier} → warp (1.4).
 */
public final class TierManager implements Listener, TabExecutor {

    public record Tier(int number, String name, long serverLevel, List<String> flags, Material icon) {}

    private final DimensionalMinerCore plugin;
    private final WarpService warps;
    private final Map<Integer, Tier> tiers = new TreeMap<>();
    private final Map<UUID, Set<String>> flags = new ConcurrentHashMap<>();

    public TierManager(DimensionalMinerCore plugin, WarpService warps) {
        this.plugin = plugin;
        this.warps = warps;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("tiers");
        if (s != null) for (String key : s.getKeys(false)) {
            int n;
            try { n = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
            if (n <= 0) continue;
            Material icon = Material.matchMaterial(s.getString(key + ".icon", "IRON_PICKAXE"));
            tiers.put(n, new Tier(n, s.getString(key + ".name", "Tier " + n), s.getLong(key + ".server_level", 1),
                    s.getStringList(key + ".flags").stream().map(f -> f.toLowerCase(Locale.ROOT)).toList(),
                    icon != null && icon.isItem() ? icon : Material.IRON_PICKAXE));
        }
        plugin.getLogger().info("[Tier] " + tiers.size() + " tier(s) configured: " + tiers.keySet());
    }

    // ------------------------------------------------------------------ unlock state

    private void loadFlags(UUID id, Consumer<Set<String>> then) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> out = new HashSet<>();
            try {
                UUID account = plugin.getDatabaseManager().getAccountId(id);
                if (account != null) try (Connection c = plugin.getDatabaseManager().getConnection();
                                          PreparedStatement ps = c.prepareStatement("SELECT unlock_id FROM player_unlocks WHERE player_id = ?")) {
                    ps.setObject(1, account);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1).toLowerCase(Locale.ROOT)); }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[Tier] Could not load unlocks of " + id + ": " + e.getMessage());
                return;
            }
            flags.put(id, out);
            if (then != null) Bukkit.getScheduler().runTask(plugin, () -> then.accept(out));
        });
    }

    /** What is still missing for a tier (empty = unlocked). Uses cached flags / Server Level. */
    public List<String> missing(Player p, int tier) {
        if (tier <= 0) return List.of();
        Tier t = tiers.get(tier);
        if (t == null) return List.of("Tier " + tier + " does not exist");
        List<String> out = new ArrayList<>();
        long level = plugin.getProgression() != null ? plugin.getProgression().serverLevel(p.getUniqueId()) : 1;
        if (level < t.serverLevel()) out.add("Server Level " + t.serverLevel() + " (you: " + level + ")");
        Set<String> have = flags.getOrDefault(p.getUniqueId(), Set.of());
        for (String f : t.flags()) if (!have.contains(f)) out.add(describeFlag(f));
        return out;
    }

    private static String describeFlag(String f) {
        if (f.startsWith("clear:")) return "Clear the dungeon " + f.substring(6);
        if (f.startsWith("quest:")) return "Main quest " + f.substring(6);
        return f;
    }

    private boolean bypass(Player p) {
        return p.hasPermission("dimensionalminer.admin") && p.getGameMode() == org.bukkit.GameMode.CREATIVE;
    }

    // ------------------------------------------------------------------ access checks

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Server Level is loaded ~2 s after join; check after both are in.
        loadFlags(p.getUniqueId(), f -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            int tier = plugin.getChannelManager().tierOf(p.getWorld().getName());
            if (tier > 0 && !bypass(p) && !missing(p, tier).isEmpty()) {
                p.sendMessage(Component.text("[Tier] Tier " + tier + " is locked for you; back to the hub.", NamedTextColor.YELLOW));
                World hub = Bukkit.getWorld(ChannelManager.HUB_WORLD);
                if (hub != null) p.teleportAsync(hub.getSpawnLocation());
            }
        }, 60L));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flags.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to.getWorld() == null || to.getWorld() == event.getFrom().getWorld()) return;
        int tier = plugin.getChannelManager().tierOf(to.getWorld().getName());
        if (tier <= 0 || bypass(event.getPlayer())) return;
        List<String> miss = missing(event.getPlayer(), tier);
        if (miss.isEmpty()) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("[Tier] Tier " + tier + " is locked: " + String.join(", ", miss), NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ travel

    private void travel(Player p, int tier) {
        if (tier != 0 && !tiers.containsKey(tier)) { p.sendMessage(Component.text("[Tier] No Tier " + tier + ".", NamedTextColor.RED)); return; }
        int here = plugin.getChannelManager().tierOf(p.getWorld().getName());
        if (here == tier) { p.sendMessage(Component.text("[Tier] You are already in " + (tier == 0 ? "the hub" : "Tier " + tier) + ".", NamedTextColor.YELLOW)); return; }
        List<String> miss = bypass(p) ? List.of() : missing(p, tier);
        if (!miss.isEmpty()) { p.sendMessage(Component.text("[Tier] Locked: " + String.join(", ", miss), NamedTextColor.RED)); return; }
        String label = tier == 0 ? "Hub" : tiers.get(tier).name();
        warps.start(p, label, () -> {
            // re-check at the end of the countdown
            List<String> again = bypass(p) ? List.of() : missing(p, tier);
            if (!again.isEmpty()) { p.sendMessage(Component.text("[Tier] Locked: " + String.join(", ", again), NamedTextColor.RED)); return null; }
            // 1.3: the party's channel if it has room, else the least busy one (opens another if all are full)
            String w = plugin.getChannelManager().pick(p, tier);
            World world = w == null ? null : Bukkit.getWorld(w);
            if (world == null) world = Bukkit.getWorld(tier == 0 ? ChannelManager.HUB_WORLD : ChannelManager.tierWorld(tier));
            if (world == null) { p.sendMessage(Component.text("[Tier] " + label + " is not loaded yet. Try again shortly.", NamedTextColor.RED)); return null; }
            return world.getSpawnLocation();
        });
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Integer> slotTier = new TreeMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private void open(Player p) {
        Menu m = new Menu();
        int size = Math.max(9, (int) Math.ceil((tiers.size() + 1) / 9.0) * 9);
        m.inv = Bukkit.createInventory(m, Math.min(54, size), Component.text("Dimensions (Tiers)"));
        int here = plugin.getChannelManager().tierOf(p.getWorld().getName());
        m.inv.setItem(0, icon(Material.LODESTONE, "Hub (Tier 0)", NamedTextColor.GREEN, List.of(
                line("Always open", NamedTextColor.GREEN), line(here == 0 ? "You are here" : "Click to warp", NamedTextColor.GRAY))));
        m.slotTier.put(0, 0);
        int slot = 1;
        for (Tier t : tiers.values()) {
            if (slot >= m.inv.getSize()) break;
            List<String> miss = missing(p, t.number());
            List<Component> lore = new ArrayList<>();
            long level = plugin.getProgression() != null ? plugin.getProgression().serverLevel(p.getUniqueId()) : 1;
            lore.add(line((level >= t.serverLevel() ? "✔ " : "✘ ") + "Server Level " + t.serverLevel(),
                    level >= t.serverLevel() ? NamedTextColor.GREEN : NamedTextColor.RED));
            Set<String> have = flags.getOrDefault(p.getUniqueId(), Set.of());
            for (String f : t.flags()) lore.add(line((have.contains(f) ? "✔ " : "✘ ") + describeFlag(f),
                    have.contains(f) ? NamedTextColor.GREEN : NamedTextColor.RED));
            lore.add(Component.empty());
            lore.add(line(here == t.number() ? "You are here" : miss.isEmpty() ? "Click to warp (" + "prepare, don't move)" : "Locked",
                    miss.isEmpty() ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
            m.inv.setItem(slot, icon(miss.isEmpty() ? t.icon() : Material.BARRIER, t.name() + " (Tier " + t.number() + ")",
                    miss.isEmpty() ? NamedTextColor.AQUA : NamedTextColor.GRAY, lore));
            m.slotTier.put(slot, t.number());
            slot++;
        }
        p.openInventory(m.inv);
    }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack icon(Material mat, String name, NamedTextColor c, List<Component> lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.displayName(line(name, c).decorate(TextDecoration.BOLD));
        m.lore(lore);
        i.setItemMeta(m);
        return i;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        Integer tier = m.slotTier.get(event.getSlot());
        if (tier == null) return;
        p.closeInventory();
        travel(p, tier);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ /tier

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("grant") || sub.equals("revoke") || sub.equals("flags")) return admin(sender, sub, args);
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (sub.isEmpty()) { loadFlags(p.getUniqueId(), f -> open(p)); return true; }
        int tier;
        try { tier = sub.equals("hub") ? 0 : Integer.parseInt(sub); } catch (NumberFormatException e) {
            p.sendMessage(Component.text("Usage: /tier [hub|<number>]", NamedTextColor.YELLOW));
            return true;
        }
        loadFlags(p.getUniqueId(), f -> travel(p, tier));
        return true;
    }

    private boolean admin(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission("dimensionalminer.admin")) { sender.sendMessage(Component.text("No permission.", NamedTextColor.RED)); return true; }
        if (args.length < 2 || (!sub.equals("flags") && args.length < 3)) {
            sender.sendMessage(Component.text("Usage: /tier grant|revoke <player> <flag>  ·  /tier flags <player>  (flags: quest:<id>, clear:<dungeon>)", NamedTextColor.YELLOW));
            return true;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID id = target.getUniqueId();
        String flag = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String result;
            try {
                UUID account = plugin.getDatabaseManager().getAccountId(id);
                if (account == null) result = "Unknown player " + args[1] + " (has never joined).";
                else try (Connection c = plugin.getDatabaseManager().getConnection()) {
                    switch (sub) {
                        case "grant" -> {
                            try (PreparedStatement ps = c.prepareStatement("INSERT INTO player_unlocks (player_id, unlock_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                                ps.setObject(1, account); ps.setString(2, flag);
                                result = ps.executeUpdate() > 0 ? "Granted " + flag + " to " + args[1] + "." : args[1] + " already has " + flag + ".";
                            }
                        }
                        case "revoke" -> {
                            try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_unlocks WHERE player_id = ? AND unlock_id = ?")) {
                                ps.setObject(1, account); ps.setString(2, flag);
                                result = ps.executeUpdate() > 0 ? "Revoked " + flag + " from " + args[1] + "." : args[1] + " doesn't have " + flag + ".";
                            }
                        }
                        default -> {
                            List<String> all = new ArrayList<>();
                            try (PreparedStatement ps = c.prepareStatement("SELECT unlock_id FROM player_unlocks WHERE player_id = ? ORDER BY unlock_id")) {
                                ps.setObject(1, account);
                                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) all.add(rs.getString(1)); }
                            }
                            result = args[1] + ": " + (all.isEmpty() ? "no unlocks" : String.join(", ", all));
                        }
                    }
                }
            } catch (SQLException e) {
                result = "Failed: " + e.getMessage();
            }
            String msg = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(Component.text("[Tier] " + msg, NamedTextColor.GRAY));
                if (Bukkit.getPlayer(id) != null) loadFlags(id, null);
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> o = new ArrayList<>(List.of("hub"));
            tiers.keySet().forEach(t -> o.add(String.valueOf(t)));
            if (sender.hasPermission("dimensionalminer.admin")) o.addAll(List.of("grant", "revoke", "flags"));
            return o.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("grant", "revoke", "flags").contains(args[0].toLowerCase(Locale.ROOT)))
            return null; // player names
        if (args.length == 3 && args[0].equalsIgnoreCase("grant")) {
            Set<String> fl = new java.util.TreeSet<>();
            tiers.values().forEach(t -> fl.addAll(t.flags()));
            return fl.stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
