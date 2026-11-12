package com.dimensionalminer.core.dimensionalMinerCore.worldboss;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World Boss (7.1 – 7.4). Every {@code interval_minutes} a {@link WorldBossDefinition} spawns once in each
 * loaded channel of its Tier (one {@code world_encounters} row per channel + boss + window; the unique key
 * stops a restart from spawning the same window twice).
 *
 * <ul>
 *   <li>7.1 Contribution: real damage per player (overkill excluded, pets and procs count), first-hit time
 *       and active time (hits less than 10 s apart). Eligible = damage ≥ min_damage_percent of max HP and
 *       active ≥ min_active_seconds.</li>
 *   <li>7.2 Rewards: every eligible player gets the standard rewards; the set Top 3 damage ∪ Last Shot
 *       (eligible only, ties → earlier first hit → UUID) gets the rare rewards once each.</li>
 *   <li>7.3 Server EXP: {@code E_i = pool × (0.30/N + 0.70 × d_i/Σd)}, largest remainder so the sum is
 *       exactly the pool.</li>
 *   <li>7.4 Quota: {@code reward_claim_scopes} key {@code worldboss/<boss>/<window>} per account, so a
 *       player is paid once per window across all channels (they can still help in other channels).</li>
 * </ul>
 * Each player's payout is one transaction: operation + mailbox grant (items) + claim scope + ledger credits.
 */
public final class WorldBossManager implements Listener, CommandExecutor, TabCompleter {

    public static final String REWARD_ACCOUNT = "WORLD_BOSS_REWARDS";
    private static final long ACTIVE_GAP_MS = 10_000;

    private static final class Contrib {
        double damage;
        long firstHit;
        long lastHit;
        long activeMs;
    }

    private static final class Encounter {
        final UUID id;
        final WorldBossDefinition def;
        final String world;
        final String window;
        final Location home;
        final long spawnedAt = System.currentTimeMillis();
        final Map<UUID, Contrib> contrib = new LinkedHashMap<>();
        final BossBar bar;
        LivingEntity boss;
        UUID lastHitter;
        boolean ended;

        Encounter(UUID id, WorldBossDefinition def, String world, String window, Location home) {
            this.id = id;
            this.def = def;
            this.world = world;
            this.window = window;
            this.home = home;
            this.bar = BossBar.bossBar(Component.text(def.displayName(), NamedTextColor.RED), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        }

        String key() { return key(world, def.id()); }
        static String key(String world, String boss) { return world + "|" + boss; }
    }

    private final DimensionalMinerCore plugin;
    private final NamespacedKey kBoss;
    /** world|boss → live encounter (at most one per channel and boss). */
    private final Map<String, Encounter> live = new HashMap<>();
    private final Map<UUID, Encounter> byEntity = new HashMap<>();
    /** world|boss → last scheduled window already handled (spawned, or found taken in the DB). */
    private final Map<String, Long> handledWindow = new HashMap<>();
    private final Set<String> spawning = new HashSet<>();
    private volatile boolean ready = false;

    public WorldBossManager(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        this.kBoss = new NamespacedKey(plugin, "world_boss_id");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            abortStaleEncounters();
            ready = true;
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    // ------------------------------------------------------------------ schedule / upkeep (main thread)

    private int ticks;

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        for (Encounter e : List.copyOf(live.values())) upkeep(e, now);
        if (!ready || ticks % 10 != 0) return; // schedule check every 10 s
        var channels = plugin.getChannelManager();
        if (channels == null) return;
        for (WorldBossDefinition def : plugin.getContentRegistryManager().getAllWorldBosses()) {
            long window = now / (def.intervalMinutes() * 60_000L);
            for (String worldName : channels.familyWorlds(def.tier())) {
                World w = Bukkit.getWorld(worldName);
                if (w == null) continue;
                String key = Encounter.key(worldName, def.id());
                if (live.containsKey(key) || spawning.contains(key)) continue;
                Long done = handledWindow.get(key);
                if (done != null && done == window) continue;
                handledWindow.put(key, window);
                spawn(def, w, "w" + def.intervalMinutes() + "m-" + window, new Location(w, def.x(), def.y(), def.z()), null);
            }
        }
    }

    private void upkeep(Encounter e, long now) {
        if (e.ended) return;
        LivingEntity b = e.boss;
        if (b == null || !b.isValid()) {
            if (b != null && b.isDead()) return; // death event handles it
            end(e, "ABORTED", "The boss vanished.");
            return;
        }
        if (now - e.spawnedAt > e.def.lifetimeMinutes() * 60_000L) {
            end(e, "ABORTED", e.def.displayName() + " retreated. Nobody gets rewards this time.");
            return;
        }
        // Leash: never dragged away from its arena.
        if (b.getLocation().distanceSquared(e.home) > e.def.leashRadius() * e.def.leashRadius()) {
            b.teleport(e.home);
            if (b instanceof Mob m) m.setTarget(null);
        }
        double max = maxHp(b);
        e.bar.progress((float) Math.clamp(b.getHealth() / max, 0, 1));
        e.bar.name(Component.text(e.def.displayName() + "  " + (int) Math.ceil(b.getHealth()) + " / " + (int) max, NamedTextColor.RED));
        World w = b.getWorld();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(b.getLocation()) < 96 * 96) p.showBossBar(e.bar); else p.hideBossBar(e.bar);
        }
    }

    // ------------------------------------------------------------------ spawn

    /** Records the encounter (async), then spawns the mob. {@code admin} gets the result message. */
    private void spawn(WorldBossDefinition def, World w, String window, Location at, CommandSender admin) {
        String key = Encounter.key(w.getName(), def.id());
        if (live.containsKey(key) || !spawning.add(key)) {
            if (admin != null) admin.sendMessage(Component.text("[WorldBoss] " + def.id() + " is already up in " + w.getName() + ".", NamedTextColor.RED));
            return;
        }
        UUID id = UUID.randomUUID();
        String worldName = w.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean inserted;
            try (Connection c = plugin.getDatabaseManager().getConnection()) {
                try (PreparedStatement n = c.prepareStatement(
                        "INSERT INTO nodes (node_id, role, status, heartbeat_at) VALUES (?, 'MAIN', 'READY', now())"
                                + " ON CONFLICT (node_id) DO UPDATE SET heartbeat_at = now()")) {
                    n.setString(1, worldName);
                    n.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO world_encounters (encounter_id, channel_id, boss_type, schedule_window, config_version, state)"
                                + " VALUES (?, ?, ?, ?, ?, 'ACTIVE') ON CONFLICT (channel_id, boss_type, schedule_window) DO NOTHING")) {
                    ins.setObject(1, id);
                    ins.setString(2, worldName);
                    ins.setString(3, def.id());
                    ins.setString(4, window);
                    ins.setInt(5, def.version());
                    inserted = ins.executeUpdate() == 1;
                }
            } catch (SQLException ex) {
                plugin.getLogger().warning("[WorldBoss] Could not record " + def.id() + " in " + worldName + ": " + ex.getMessage());
                inserted = false;
            }
            boolean ok = inserted;
            Bukkit.getScheduler().runTask(plugin, () -> {
                spawning.remove(key);
                if (!ok) {
                    if (admin != null) admin.sendMessage(Component.text("[WorldBoss] Not spawned (window already used, or DB error).", NamedTextColor.RED));
                    return;
                }
                spawnEntity(new Encounter(id, def, worldName, window, at), admin);
            });
        });
    }

    private void spawnEntity(Encounter e, CommandSender admin) {
        World w = Bukkit.getWorld(e.world);
        EntityType type;
        try {
            type = EntityType.valueOf(e.def.vanillaType());
        } catch (IllegalArgumentException ex) {
            type = EntityType.IRON_GOLEM;
            plugin.getLogger().warning("[WorldBoss] " + e.def.id() + ": unknown vanilla_type " + e.def.vanillaType() + ", using IRON_GOLEM");
        }
        if (w == null || !(w.spawnEntity(e.home, type) instanceof LivingEntity b)) {
            closeAsync(e, "ABORTED", null, List.of());
            return;
        }
        w.addPluginChunkTicket(e.home.getBlockX() >> 4, e.home.getBlockZ() >> 4, plugin);
        b.setPersistent(false);
        b.setRemoveWhenFarAway(false);
        b.customName(Component.text(e.def.displayName(), NamedTextColor.RED));
        b.setCustomNameVisible(true);
        b.getPersistentDataContainer().set(kBoss, PersistentDataType.STRING, e.def.id());
        var combat = plugin.getCombatService();
        if (combat != null) combat.applyMobStats(b, e.def.hp(), e.def.attack(), e.def.defense());
        else if (b.getAttribute(Attribute.MAX_HEALTH) != null) {
            b.getAttribute(Attribute.MAX_HEALTH).setBaseValue(e.def.hp());
            b.setHealth(e.def.hp());
        }
        e.boss = b;
        live.put(e.key(), e);
        byEntity.put(b.getUniqueId(), e);
        Component msg = Component.text("[WorldBoss] " + e.def.displayName() + " has appeared at "
                + e.home.getBlockX() + ", " + e.home.getBlockY() + ", " + e.home.getBlockZ()
                + "! It leaves in " + e.def.lifetimeMinutes() + " min.", NamedTextColor.GOLD);
        for (Player p : w.getPlayers()) p.sendMessage(msg);
        plugin.getLogger().info("[WorldBoss] " + e.def.id() + " spawned in " + e.world + " (window " + e.window + ")");
        if (admin != null) admin.sendMessage(Component.text("[WorldBoss] Spawned " + e.def.id() + " in " + e.world + " (window " + e.window + ").", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ combat hooks

    private static Player ownerPlayer(Entity damager) {
        Entity e = damager;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) e = shooter;
        if (e instanceof Tameable t && t.getOwner() instanceof Player owner) return owner;
        return e instanceof Player p ? p : null;
    }

    /** The boss only takes damage from players (and their projectiles / pets / procs). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!byEntity.containsKey(event.getEntity().getUniqueId())) return;
        if (event instanceof EntityDamageByEntityEvent ebe && ownerPlayer(ebe.getDamager()) != null) return;
        event.setCancelled(true);
    }

    /** 7.1: real damage per player after the combat pipeline (MONITOR), overkill excluded. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Encounter e = byEntity.get(event.getEntity().getUniqueId());
        if (e == null || e.ended) return;
        Player p = ownerPlayer(event.getDamager());
        if (p == null) return;
        LivingEntity b = e.boss;
        double dealt = Math.min(event.getFinalDamage(), b.getHealth() + b.getAbsorptionAmount());
        if (dealt <= 0) return;
        long now = System.currentTimeMillis();
        Contrib c = e.contrib.computeIfAbsent(p.getUniqueId(), k -> new Contrib());
        if (c.firstHit == 0) c.firstHit = now;
        else if (now - c.lastHit <= ACTIVE_GAP_MS) c.activeMs += now - c.lastHit;
        c.lastHit = now;
        c.damage += dealt;
        e.lastHitter = p.getUniqueId();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Encounter e = byEntity.get(event.getEntity().getUniqueId());
        if (e == null || e.ended) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        finish(e);
    }

    // ------------------------------------------------------------------ results (7.2 / 7.3)

    private record Result(UUID player, double damage, long firstHit, long activeMs, boolean eligible,
                          boolean rare, long serverXp, String rank) {}

    private void finish(Encounter e) {
        double maxHp = maxHp(e.boss);
        double minDamage = maxHp * e.def.minDamagePercent() / 100.0;
        long minActive = e.def.minActiveSeconds() * 1000L;

        List<UUID> eligible = new ArrayList<>();
        for (Map.Entry<UUID, Contrib> c : e.contrib.entrySet()) {
            if (c.getValue().damage >= minDamage && c.getValue().activeMs >= minActive) eligible.add(c.getKey());
        }
        // Deterministic order: damage desc → earlier first hit → UUID.
        Comparator<UUID> order = Comparator.<UUID>comparingDouble(id -> -e.contrib.get(id).damage)
                .thenComparingLong(id -> e.contrib.get(id).firstHit)
                .thenComparing(UUID::toString);
        eligible.sort(order);
        List<UUID> top3 = eligible.subList(0, Math.min(3, eligible.size()));
        UUID lastShot = e.lastHitter != null && eligible.contains(e.lastHitter) ? e.lastHitter : null;
        Set<UUID> rare = new LinkedHashSet<>(top3);
        if (lastShot != null) rare.add(lastShot);

        Map<UUID, Long> xp = splitPool(e.def.serverXpPool(), eligible, e.contrib);
        List<Result> results = new ArrayList<>();
        for (Map.Entry<UUID, Contrib> c : e.contrib.entrySet()) {
            UUID id = c.getKey();
            int place = top3.indexOf(id);
            String rank = (place >= 0 ? "Top " + (place + 1) : "") + (id.equals(lastShot) ? (place >= 0 ? " + " : "") + "Last Shot" : "");
            results.add(new Result(id, c.getValue().damage, c.getValue().firstHit, c.getValue().activeMs,
                    eligible.contains(id), rare.contains(id), xp.getOrDefault(id, 0L), rank));
        }
        e.ended = true;
        teardown(e);
        String summary = summary(e, top3, lastShot);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, String> outcome = new HashMap<>();
            for (Result r : results) if (r.eligible()) outcome.put(r.player(), payOne(e, r));
            recordFinished(e, results, top3, lastShot);
            Bukkit.getScheduler().runTask(plugin, () -> announce(e, results, outcome, summary, maxHp));
        });
    }

    /** 7.3: E_i = pool × (0.30/N + 0.70 × d_i/Σd), largest remainder so Σ E_i = pool exactly. */
    static Map<UUID, Long> splitPool(long pool, List<UUID> eligible, Map<UUID, Contrib> contrib) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (pool <= 0 || eligible.isEmpty()) return out;
        int n = eligible.size();
        double sum = 0;
        for (UUID id : eligible) sum += contrib.get(id).damage;
        double[] exact = new double[n];
        long given = 0;
        for (int i = 0; i < n; i++) {
            double share = 0.30 / n + (sum > 0 ? 0.70 * contrib.get(eligible.get(i)).damage / sum : 0.70 / n);
            exact[i] = pool * share;
            long fl = (long) Math.floor(exact[i]);
            out.put(eligible.get(i), fl);
            given += fl;
        }
        // Remainder to the largest fractional parts; eligible is already in ranking order, so ties are deterministic.
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, Comparator.comparingDouble((Integer i) -> -(exact[i] - Math.floor(exact[i]))).thenComparingInt(i -> i));
        for (int k = 0; k < pool - given; k++) out.merge(eligible.get(idx[k % n]), 1L, Long::sum);
        return out;
    }

    /**
     * Async. One transaction per player: operation (once per encounter) → quota check →
     * mailbox grant + claim scope (7.4) → currency and Server EXP credits.
     * @return null when paid, else why not
     */
    private String payOne(Encounter e, Result r) {
        List<WorldBossDefinition.Reward> rewards = new ArrayList<>(e.def.standard());
        if (r.rare()) rewards.addAll(e.def.rare());
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            UUID account = plugin.getDatabaseManager().getAccountId(r.player());
            c.setAutoCommit(false);
            try {
                String scope = "worldboss/" + e.def.id() + "/" + e.window;
                try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM reward_claim_scopes WHERE scope_key = ? AND player_id = ?")) {
                    q.setString(1, scope);
                    q.setObject(2, account);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) { c.rollback(); return "QUOTA"; }
                    }
                }
                JsonObject resp = new JsonObject();
                resp.addProperty("boss", e.def.id());
                resp.addProperty("window", e.window);
                resp.addProperty("channel", e.world);
                resp.addProperty("damage", Math.round(r.damage() * 100) / 100.0);
                resp.addProperty("rare", r.rare());
                resp.addProperty("server_xp", r.serverXp());
                UUID op = UUID.randomUUID();
                if (!Ledger.insertOperation(c, op, account, "worldboss/" + e.id, e.def.id() + "@" + e.def.version(), "WORLD_BOSS_REWARD", resp.toString())) {
                    c.rollback();
                    return null; // already paid for this encounter
                }
                Map<String, Long> items = new LinkedHashMap<>();
                Map<String, Long> money = new LinkedHashMap<>();
                for (WorldBossDefinition.Reward rw : rewards) {
                    if (rw.isItem()) items.merge(rw.item(), rw.amount(), Long::sum); else money.merge(rw.currency(), rw.amount(), Long::sum);
                }
                JsonArray arr = new JsonArray();
                items.forEach((id, n) -> {
                    JsonObject it = new JsonObject();
                    it.addProperty("item_id", id);
                    it.addProperty("amount", n);
                    arr.add(it);
                });
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "WORLD_BOSS_REWARD");
                payload.addProperty("boss_id", e.def.id());
                payload.addProperty("rare", r.rare());
                payload.add("items", arr);
                UUID grant = UUID.randomUUID();
                try (PreparedStatement g = c.prepareStatement(
                        "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE')")) {
                    g.setObject(1, grant);
                    g.setObject(2, account);
                    g.setString(3, "worldboss:" + e.id + ":" + account);
                    g.setString(4, payload.toString());
                    g.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO reward_claim_scopes (scope_key, player_id, grant_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                    s.setString(1, scope);
                    s.setObject(2, account);
                    s.setObject(3, grant);
                    if (s.executeUpdate() == 0) { c.rollback(); return "QUOTA"; } // paid in another channel just now
                }
                for (Map.Entry<String, Long> m : money.entrySet()) {
                    Ledger.Wallet from = Ledger.lockWallet(c, null, REWARD_ACCOUNT, m.getKey());
                    Ledger.Wallet to = Ledger.lockWallet(c, account, null, m.getKey());
                    Ledger.transfer(c, op, from.id(), to.id(), m.getValue(), "WORLD_BOSS");
                }
                if (r.serverXp() > 0) {
                    Ledger.Wallet from = Ledger.lockWallet(c, null, "SERVER_XP_SOURCE", "SERVER_XP");
                    Ledger.Wallet to = Ledger.lockWallet(c, account, null, "SERVER_XP");
                    Ledger.transfer(c, op, from.id(), to.id(), r.serverXp(), "WORLD_BOSS");
                }
                c.commit();
                return null;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("[WorldBoss] Reward of " + e.def.id() + " (" + e.id + ") for " + r.player() + " failed: " + ex.getMessage());
            return "ERROR";
        }
    }

    /** Async. FINISHED + outcome JSON + one encounter_contributions row per participant. */
    private void recordFinished(Encounter e, List<Result> results, List<UUID> top3, UUID lastShot) {
        JsonObject outcome = new JsonObject();
        JsonArray top = new JsonArray();
        top3.forEach(id -> top.add(id.toString()));
        outcome.add("top3", top);
        outcome.addProperty("last_shot", lastShot == null ? null : lastShot.toString());
        outcome.addProperty("participants", results.size());
        outcome.addProperty("eligible", results.stream().filter(Result::eligible).count());
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement u = c.prepareStatement(
                        "UPDATE world_encounters SET state = 'FINISHED', last_hit_player_id = ?, outcome = ?::jsonb WHERE encounter_id = ?")) {
                    u.setObject(1, e.lastHitter == null ? null : plugin.getDatabaseManager().getAccountId(e.lastHitter));
                    u.setString(2, outcome.toString());
                    u.setObject(3, e.id);
                    u.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO encounter_contributions (encounter_id, player_id, damage_units, support_units, active_ms, tie_break_at)"
                                + " VALUES (?, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING")) {
                    for (Result r : results) {
                        ins.setObject(1, e.id);
                        ins.setObject(2, plugin.getDatabaseManager().getAccountId(r.player()));
                        ins.setLong(3, Math.round(r.damage() * 100)); // 1 unit = 0.01 HP
                        ins.setLong(4, r.activeMs());
                        ins.setTimestamp(5, new java.sql.Timestamp(r.firstHit()));
                        ins.addBatch();
                    }
                    if (!results.isEmpty()) ins.executeBatch();
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("[WorldBoss] Could not record the result of " + e.id + ": " + ex.getMessage());
        }
    }

    private String summary(Encounter e, List<UUID> top3, UUID lastShot) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top3.size(); i++) {
            UUID id = top3.get(i);
            sb.append(i == 0 ? "" : "   ").append("#").append(i + 1).append(" ").append(name(id))
                    .append(" (").append((int) Math.round(e.contrib.get(id).damage)).append(")");
        }
        if (lastShot != null) sb.append("   Last Shot: ").append(name(lastShot));
        return sb.isEmpty() ? "No eligible players." : sb.toString();
    }

    private void announce(Encounter e, List<Result> results, Map<UUID, String> outcome, String summary, double maxHp) {
        World w = Bukkit.getWorld(e.world);
        Component head = Component.text("[WorldBoss] " + e.def.displayName() + " defeated!  ", NamedTextColor.GOLD)
                .append(Component.text(summary, NamedTextColor.YELLOW));
        if (w != null) for (Player p : w.getPlayers()) p.sendMessage(head);
        for (Result r : results) {
            Player p = Bukkit.getPlayer(r.player());
            if (p == null) continue;
            String pct = String.format(Locale.ROOT, "%.1f%%", 100 * r.damage() / Math.max(1, maxHp));
            if (!r.eligible()) {
                p.sendMessage(Component.text("[WorldBoss] Not eligible: " + pct + " damage (need " + e.def.minDamagePercent()
                        + "%), active " + r.activeMs() / 1000 + "s (need " + e.def.minActiveSeconds() + "s).", NamedTextColor.GRAY));
                continue;
            }
            String why = outcome.get(r.player());
            if ("QUOTA".equals(why)) {
                p.sendMessage(Component.text("[WorldBoss] You already got this boss's rewards this window (another channel). Thanks for the help!", NamedTextColor.YELLOW));
            } else if (why != null) {
                p.sendMessage(Component.text("[WorldBoss] Your reward could not be recorded. Please tell an admin.", NamedTextColor.RED));
            } else {
                List<String> got = new ArrayList<>();
                e.def.standard().forEach(x -> got.add(x.describe()));
                if (r.rare()) e.def.rare().forEach(x -> got.add(x.describe()));
                if (r.serverXp() > 0) got.add(r.serverXp() + " Server XP");
                p.sendMessage(Component.text("[WorldBoss] " + pct + " damage" + (r.rank().isEmpty() ? "" : " · " + r.rank())
                        + (r.rare() ? " · RARE reward" : "") + ": " + String.join(", ", got) + ". Items are in /grants.", NamedTextColor.GREEN));
            }
        }
    }

    // ------------------------------------------------------------------ end / cleanup

    /** Main thread. Ends without rewards (time up, admin, vanished). */
    private void end(Encounter e, String state, String message) {
        if (e.ended) return;
        e.ended = true;
        if (e.boss != null && e.boss.isValid()) e.boss.remove();
        teardown(e);
        World w = Bukkit.getWorld(e.world);
        if (w != null && message != null) for (Player p : w.getPlayers()) p.sendMessage(Component.text("[WorldBoss] " + message, NamedTextColor.GRAY));
        closeAsync(e, state, null, List.of());
    }

    private void teardown(Encounter e) {
        live.remove(e.key());
        if (e.boss != null) byEntity.remove(e.boss.getUniqueId());
        World w = Bukkit.getWorld(e.world);
        if (w != null) {
            w.removePluginChunkTicket(e.home.getBlockX() >> 4, e.home.getBlockZ() >> 4, plugin);
            for (Player p : w.getPlayers()) p.hideBossBar(e.bar);
        }
    }

    private void closeAsync(Encounter e, String state, UUID lastHit, List<Result> ignored) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> setState(e.id, state));
    }

    private void setState(UUID encounterId, String state) {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement u = c.prepareStatement("UPDATE world_encounters SET state = ? WHERE encounter_id = ? AND state IN ('SPAWNED','ACTIVE')")) {
            u.setString(1, state);
            u.setObject(2, encounterId);
            u.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("[WorldBoss] encounter " + encounterId + " → " + state + ": " + ex.getMessage());
        }
    }

    /** Startup: encounters left open by a stop or crash (their mobs are gone) become ABORTED. */
    private void abortStaleEncounters() {
        try (Connection c = plugin.getDatabaseManager().getConnection();
             PreparedStatement u = c.prepareStatement("UPDATE world_encounters SET state = 'ABORTED' WHERE state IN ('SPAWNED','ACTIVE')")) {
            int n = u.executeUpdate();
            if (n > 0) plugin.getLogger().info("[WorldBoss] Closed " + n + " encounter(s) left open by the last stop (ABORTED).");
        } catch (SQLException ex) {
            plugin.getLogger().warning("[WorldBoss] Could not close old encounters: " + ex.getMessage());
        }
    }

    /** onDisable: remove the mobs and mark their encounters ABORTED (blocking; schedulers are stopped). */
    public void shutdown() {
        for (Encounter e : List.copyOf(live.values())) {
            e.ended = true;
            if (e.boss != null && e.boss.isValid()) e.boss.remove();
            teardown(e);
            setState(e.id, "ABORTED");
        }
    }

    private static double maxHp(LivingEntity b) {
        var a = b.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : Math.max(1, b.getHealth());
    }

    private static String name(UUID id) {
        var p = Bukkit.getOfflinePlayer(id);
        return p.getName() != null ? p.getName() : id.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ /wboss (admin)

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dimensionalminer.admin") && !sender.isOp()) {
            sender.sendMessage(Component.text("Admins only.", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                sender.sendMessage(Component.text("=== World Bosses ===", NamedTextColor.GOLD));
                long now = System.currentTimeMillis();
                for (WorldBossDefinition d : plugin.getContentRegistryManager().getAllWorldBosses()) {
                    long period = d.intervalMinutes() * 60_000L;
                    long next = (now / period + 1) * period - now;
                    sender.sendMessage(Component.text("- " + d.id() + " (" + d.displayName() + ") tier " + d.tier() + " · every "
                            + d.intervalMinutes() + " min (next window in " + next / 60_000 + "m " + next / 1000 % 60 + "s) · "
                            + (int) d.hp() + " HP", NamedTextColor.YELLOW));
                }
                for (Encounter e : live.values()) {
                    LivingEntity b = e.boss;
                    sender.sendMessage(Component.text("  • live: " + e.def.id() + " in " + e.world + " · "
                            + (b != null ? (int) b.getHealth() + "/" + (int) maxHp(b) + " HP" : "?") + " · " + e.contrib.size()
                            + " attacker(s) · window " + e.window, NamedTextColor.AQUA));
                }
            }
            case "spawn" -> {
                if (args.length < 2) { sender.sendMessage(Component.text("Usage: /" + label + " spawn <id> [here]", NamedTextColor.RED)); return true; }
                WorldBossDefinition d = plugin.getContentRegistryManager().getWorldBoss(args[1]);
                if (d == null) { sender.sendMessage(Component.text("Unknown world boss: " + args[1], NamedTextColor.RED)); return true; }
                World w;
                Location at;
                if (sender instanceof Player p) {
                    w = p.getWorld();
                    at = args.length >= 3 && args[2].equalsIgnoreCase("here") ? p.getLocation() : new Location(w, d.x(), d.y(), d.z());
                } else {
                    List<String> fam = plugin.getChannelManager().familyWorlds(d.tier());
                    w = fam.isEmpty() ? null : Bukkit.getWorld(fam.get(0));
                    if (w == null) { sender.sendMessage(Component.text("No world for tier " + d.tier(), NamedTextColor.RED)); return true; }
                    at = new Location(w, d.x(), d.y(), d.z());
                }
                // Own window per admin spawn: its quota is separate from the scheduled windows.
                spawn(d, w, "admin-" + System.currentTimeMillis() / 1000, at, sender);
            }
            case "despawn", "kill" -> {
                String which = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
                int n = 0;
                for (Encounter e : List.copyOf(live.values())) {
                    if (which.equals("all") || e.def.id().equals(which)) {
                        end(e, "ABORTED", e.def.displayName() + " was removed by an admin.");
                        n++;
                    }
                }
                sender.sendMessage(Component.text("[WorldBoss] Removed " + n + " boss(es) (no rewards).", NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("/" + label + " list | spawn <id> [here] | despawn [id|all]", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("list", "spawn", "despawn"), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("spawn") || args[0].equalsIgnoreCase("despawn"))) {
            List<String> ids = new ArrayList<>();
            plugin.getContentRegistryManager().getAllWorldBosses().forEach(d -> ids.add(d.id()));
            if (args[0].equalsIgnoreCase("despawn")) ids.add("all");
            return filter(ids, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("spawn")) return filter(List.of("here"), args[2]);
        return List.of();
    }

    private static List<String> filter(List<String> list, String prefix) {
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
