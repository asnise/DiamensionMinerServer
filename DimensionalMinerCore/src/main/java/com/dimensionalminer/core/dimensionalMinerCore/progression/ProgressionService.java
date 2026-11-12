package com.dimensionalminer.core.dimensionalMinerCore.progression;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Energy from vanilla EXP (9.3) and Server Level (9.4, also called "Core"), two separate ledgers (GDD 10):
 * <ul>
 *   <li>Picking up EXP orbs credits ENERGY (progression.energy_per_xp) instead of vanilla XP; the vanilla
 *       XP bar shows the Energy balance (client-side only, real vanilla XP stays 0). Enchanting tables,
 *       anvils and grindstones are closed and Mending is off, since they would spend or make vanilla XP.</li>
 *   <li>SERVER_XP comes from mining nodes, killing mobs (capped per hour), crafting/forging (per Energy
 *       spent) and dungeon clears (in the dungeon's reward transaction). Server Level = level of the
 *       SERVER_XP balance on the curve base × level^growth. It unlocks content; it never adds damage.</li>
 * </ul>
 * Gains are batched in memory and committed every 3 s in one double-entry transaction per player
 * (WORLD_XP → ENERGY, SERVER_XP_SOURCE → SERVER_XP).
 */
public final class ProgressionService implements Listener, CommandExecutor {

    private record Balances(long energy, long serverXp) {}

    private final DimensionalMinerCore plugin;
    private final double energyPerXp;
    private final long base, maxLevel, killCapPerHour, mineDefault, killDefault;
    private final double growth, craftEnergyPerXp;
    private final Map<String, Long> mineXp = new HashMap<>(), killXp = new HashMap<>();

    private final Map<UUID, Long> pendingEnergy = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingXp = new ConcurrentHashMap<>();
    private final Map<UUID, Balances> cache = new ConcurrentHashMap<>();
    /** player → (hour, server xp from kills this hour) */
    private final Map<UUID, long[]> killBudget = new HashMap<>();
    private final Map<UUID, Double> craftRemainder = new HashMap<>();

    public ProgressionService(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        var c = plugin.getConfig();
        energyPerXp = Math.max(0, c.getDouble("progression.energy_per_xp", 1));
        base = Math.max(1, c.getLong("progression.server_level.base", 100));
        growth = Math.max(1, c.getDouble("progression.server_level.growth", 1.5));
        maxLevel = Math.max(1, c.getLong("progression.server_level.max", 100));
        mineDefault = c.getLong("progression.server_xp.mine_default", 5);
        killDefault = c.getLong("progression.server_xp.kill_default", 1);
        killCapPerHour = c.getLong("progression.server_xp.kill_cap_per_hour", 300);
        craftEnergyPerXp = Math.max(1, c.getDouble("progression.server_xp.craft_energy_per_xp", 10));
        read(c.getConfigurationSection("progression.server_xp.mine"), mineXp);
        read(c.getConfigurationSection("progression.server_xp.kill"), killXp);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 60L);
    }

    private static void read(ConfigurationSection s, Map<String, Long> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) into.put(k.toLowerCase(Locale.ROOT), s.getLong(k));
    }

    // ------------------------------------------------------------------ level curve

    /** SERVER_XP needed to go from level n to n+1. */
    public long xpForNext(long level) {
        return Math.max(1, Math.round(base * Math.pow(level, growth)));
    }

    /** Server Level for a SERVER_XP total (starts at 1). */
    public long levelOf(long xp) {
        long level = 1;
        while (level < maxLevel) {
            long need = xpForNext(level);
            if (xp < need) break;
            xp -= need;
            level++;
        }
        return level;
    }

    /** {xp into the current level, xp needed for the next level}. */
    public long[] progress(long xp) {
        long level = 1;
        while (level < maxLevel) {
            long need = xpForNext(level);
            if (xp < need) return new long[]{xp, need};
            xp -= need;
            level++;
        }
        return new long[]{0, 0};
    }

    /** Cached Server Level of an online player (1 until loaded). */
    public long serverLevel(UUID player) {
        Balances b = cache.get(player);
        return b == null ? 1 : levelOf(b.serverXp());
    }

    // ------------------------------------------------------------------ gains

    public void addServerXp(Player p, long amount) {
        if (amount > 0) pendingXp.merge(p.getUniqueId(), amount, Long::sum);
    }

    /** Server EXP for a mined node of an ore type (progression.server_xp.mine.&lt;ore&gt; or mine_default). */
    public void onMine(Player p, String oreId) {
        addServerXp(p, mineXp.getOrDefault(oreId == null ? "" : oreId.toLowerCase(Locale.ROOT), mineDefault));
    }

    /** Server EXP for Energy spent on crafting / forging (craft_energy_per_xp Energy = 1 XP, remainder kept). */
    public void onEnergySpent(UUID player, long energy) {
        if (energy <= 0) return;
        double total = craftRemainder.getOrDefault(player, 0.0) + energy / craftEnergyPerXp;
        long whole = (long) Math.floor(total);
        craftRemainder.put(player, total - whole);
        Player p = Bukkit.getPlayer(player);
        if (whole > 0 && p != null) addServerXp(p, whole);
    }

    /** A commit elsewhere changed the Energy balance; show it without waiting for the next reload. */
    public void noteEnergy(UUID player, long energy) {
        if (energy < 0) return;
        Balances b = cache.get(player);
        cache.put(player, new Balances(energy, b == null ? 0 : b.serverXp()));
        Player p = Bukkit.getPlayer(player);
        if (p != null) hud(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        int amount = event.getAmount();
        if (amount <= 0) return;
        event.setAmount(0); // vanilla XP never accumulates
        long energy = Math.round(amount * energyPerXp);
        if (energy > 0) pendingEnergy.merge(event.getPlayer().getUniqueId(), energy, Long::sum);
    }

    /** Mending would turn orbs into vanilla repairs before they reach Energy. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player) return;
        long amount = killXp.getOrDefault(event.getEntityType().name().toLowerCase(Locale.ROOT), killDefault);
        if (amount <= 0) return;
        long hour = System.currentTimeMillis() / 3_600_000L;
        long[] budget = killBudget.computeIfAbsent(killer.getUniqueId(), k -> new long[]{hour, 0});
        if (budget[0] != hour) { budget[0] = hour; budget[1] = 0; }
        long allowed = Math.min(amount, Math.max(0, killCapPerHour - budget[1]));
        if (allowed <= 0) return;
        budget[1] += allowed;
        addServerXp(killer, allowed);
    }

    /** Enchanting, anvils and grindstones run on vanilla XP, which no longer exists. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        InventoryType t = event.getInventory().getType();
        if (t != InventoryType.ENCHANTING && t != InventoryType.ANVIL && t != InventoryType.GRINDSTONE) return;
        if (!(event.getPlayer() instanceof Player p) || p.getGameMode() == GameMode.CREATIVE) return;
        event.setCancelled(true);
        p.sendActionBar(Component.text("EXP is Energy here: use /forge and /craft instead", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------ lifecycle / HUD

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        int vanilla = p.getTotalExperience();
        if (vanilla > 0) pendingEnergy.merge(p.getUniqueId(), Math.round(vanilla * energyPerXp), Long::sum); // one-time conversion
        p.setLevel(0);
        p.setExp(0);
        p.setTotalExperience(0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> reload(p.getUniqueId(), false), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
        craftRemainder.remove(event.getPlayer().getUniqueId());
        // pending gains are still flushed by the next tick (the account exists in the DB)
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> hud(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> hud(event.getPlayer()), 2L);
    }

    private int ticks;

    private void tick() {
        ticks++;
        java.util.Set<UUID> ids = new java.util.HashSet<>(pendingEnergy.keySet());
        ids.addAll(pendingXp.keySet());
        for (UUID id : ids) reload(id, true);
        if (ticks % 2 == 0) // every 6 s refresh the rest (balances change from crafting, the shop, dungeons…)
            for (Player p : Bukkit.getOnlinePlayers()) if (!ids.contains(p.getUniqueId())) reload(p.getUniqueId(), false);
    }

    /** Flushes pending gains (if asked) and reloads both balances, then updates the HUD on the main thread. */
    private void reload(UUID id, boolean flush) {
        long e = flush ? take(pendingEnergy, id) : 0, x = flush ? take(pendingXp, id) : 0;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Balances b = null;
            try {
                UUID account = plugin.getDatabaseManager().getAccountId(id);
                if (account == null) { restore(id, e, x); return; }
                if (e > 0 || x > 0) credit(account, e, x);
                Map<String, Long> bal = plugin.getDatabaseManager().getBalances(account);
                b = new Balances(bal.getOrDefault("ENERGY", 0L), bal.getOrDefault("SERVER_XP", 0L));
            } catch (SQLException ex) {
                plugin.getLogger().warning("[Progression] " + id + ": " + ex.getMessage());
                restore(id, e, x);
                return;
            }
            Balances result = b;
            Bukkit.getScheduler().runTask(plugin, () -> apply(id, result));
        });
    }

    private static long take(Map<UUID, Long> m, UUID id) {
        Long v = m.remove(id);
        return v == null ? 0 : v;
    }

    private void restore(UUID id, long e, long x) {
        if (e > 0) pendingEnergy.merge(id, e, Long::sum);
        if (x > 0) pendingXp.merge(id, x, Long::sum);
    }

    /** One transaction: operation + double-entry credits from the system source accounts. */
    private void credit(UUID account, long energy, long xp) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            conn.setAutoCommit(false);
            try {
                UUID op = UUID.randomUUID();
                String body = "{\"energy\":" + energy + ",\"server_xp\":" + xp + "}";
                Ledger.insertOperation(conn, op, account, "progress/" + op, "progress", "PROGRESSION", body);
                if (energy > 0) {
                    Ledger.Wallet from = Ledger.lockWallet(conn, null, "WORLD_XP", "ENERGY");
                    Ledger.Wallet to = Ledger.lockWallet(conn, account, null, "ENERGY");
                    Ledger.transfer(conn, op, from.id(), to.id(), energy, "XP_ORBS");
                }
                if (xp > 0) {
                    Ledger.Wallet from = Ledger.lockWallet(conn, null, "SERVER_XP_SOURCE", "SERVER_XP");
                    Ledger.Wallet to = Ledger.lockWallet(conn, account, null, "SERVER_XP");
                    Ledger.transfer(conn, op, from.id(), to.id(), xp, "SERVER_XP");
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void apply(UUID id, Balances now) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) { cache.remove(id); return; }
        Balances before = cache.put(id, now);
        if (before != null) {
            long oldLevel = levelOf(before.serverXp()), newLevel = levelOf(now.serverXp());
            if (newLevel > oldLevel) {
                p.showTitle(Title.title(Component.text("Server Level " + newLevel, NamedTextColor.GOLD),
                        Component.text("New content may be unlocked", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
        }
        hud(p);
    }

    /** XP bar = Energy (number) · tab-list footer = Server Level and progress. */
    private void hud(Player p) {
        Balances b = cache.get(p.getUniqueId());
        if (b == null) return;
        p.sendExperienceChange(0f, (int) Math.min(Integer.MAX_VALUE, b.energy()));
        long[] pr = progress(b.serverXp());
        long level = levelOf(b.serverXp());
        p.sendPlayerListFooter(Component.text("Server Level " + level, NamedTextColor.GOLD)
                .append(Component.text(pr[1] > 0 ? "  ·  " + pr[0] + " / " + pr[1] + " XP" : "  ·  MAX", NamedTextColor.GRAY))
                .append(Component.text("  ·  Energy " + b.energy(), NamedTextColor.AQUA)));
    }

    // ------------------------------------------------------------------ /level

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        Balances b = cache.get(p.getUniqueId());
        if (b == null) { p.sendMessage(Component.text("[Level] Loading, try again in a moment.", NamedTextColor.GRAY)); return true; }
        long level = levelOf(b.serverXp());
        long[] pr = progress(b.serverXp());
        p.sendMessage(Component.text("[Level] Server Level " + level + (pr[1] > 0 ? " (" + pr[0] + " / " + pr[1] + " XP to " + (level + 1) + ")" : " (max)"), NamedTextColor.GOLD));
        p.sendMessage(Component.text("  Total Server XP " + b.serverXp() + " · Energy " + b.energy()
                + " · Server Level unlocks content, it does not add damage.", NamedTextColor.GRAY));
        p.sendMessage(Component.text("  Server XP: mining nodes, dungeon clears, killing mobs (max " + killCapPerHour
                + "/h), crafting & forging (1 per " + (long) craftEnergyPerXp + " Energy).", NamedTextColor.DARK_GRAY));
        return true;
    }
}
