package com.dimensionalminer.core.dimensionalMinerCore.craft;

import com.dimensionalminer.core.dimensionalMinerCore.ContentRegistryManager;
import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Shared engine for Craft, Advancement and Refine.
 * <p>
 * Flow for every action (one at a time per player):
 * <ol>
 *   <li>main thread: re-check the quote, take the inputs (and the gear) out of the inventory,</li>
 *   <li>async: one DB transaction — operation (idempotent per quote), Energy moved from the player
 *       to the CRAFTING_FEES system wallet (double entry, balance re-checked under FOR UPDATE),
 *       item_transactions rows with the gear id and the result,</li>
 *   <li>main thread: give the result, or give everything back if the transaction failed.</li>
 * </ol>
 * Random rolls (output chance, Refine) happen once per quote and are stored in the operation;
 * a failed commit discards them and a new quote rolls again, so a retry can never re-roll a
 * committed result.
 */
public final class CraftService {

    public static final String FEE_ACCOUNT = "CRAFTING_FEES";
    public static final String ADMIN_ACCOUNT = "ADMIN_GRANTS";

    public enum Status { OK, DUPLICATE, NO_ENERGY, ERROR }

    public record Result(Status status, long energyAfter) {
        public boolean ok() { return status == Status.OK; }
    }

    public record ItemTx(String definitionId, String action, JsonObject metadata) {}

    /** energy = amount of {@code currency} charged to CRAFTING_FEES (ENERGY unless stated). */
    public record Commit(UUID minecraftId, String idempotencyKey, String requestHash, String commandType,
                         long energy, List<ItemTx> transactions, JsonObject response, String currency) {
        public Commit(UUID minecraftId, String idempotencyKey, String requestHash, String commandType,
                      long energy, List<ItemTx> transactions, JsonObject response) {
            this(minecraftId, idempotencyKey, requestHash, commandType, energy, transactions, response, "ENERGY");
        }
    }

    private final DimensionalMinerCore plugin;
    private final ContentRegistryManager registry;
    private final GearItems gear;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public CraftService(DimensionalMinerCore plugin, GearItems gear) {
        this.plugin = plugin;
        this.registry = plugin.getContentRegistryManager();
        this.gear = gear;
        this.heatRules = HeatGame.Rules.from(plugin.getConfig());
    }

    private final HeatGame.Rules heatRules;

    public HeatGame.Rules heatRules() { return heatRules; }
    public GearItems gear() { return gear; }
    public ForgeRules rules() { return gear.rules(); }
    public DimensionalMinerCore plugin() { return plugin; }

    /** Claims the per-player lock; false if an action is already running. */
    public boolean tryBegin(UUID player) { return busy.add(player); }
    public void end(UUID player) { busy.remove(player); }
    public boolean isBusy(UUID player) { return busy.contains(player); }

    // ------------------------------------------------------------------ inventory (main thread)

    /** True if the stack is usable as the given material (gear is never consumed as material). */
    public boolean matches(ItemStack stack, String itemId) {
        if (stack == null || stack.isEmpty() || gear.read(stack) != null) return false;
        String defId = gear.definitionId(stack);
        if (registry.hasItem(itemId)) return itemId.equalsIgnoreCase(defId);
        Material m = Material.matchMaterial(itemId.toUpperCase(Locale.ROOT));
        return defId == null && m != null && stack.getType() == m;
    }

    public int count(Player player, String itemId) {
        int n = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) if (matches(s, itemId)) n += s.getAmount();
        return n;
    }

    /** Removes the amounts from the main inventory and returns what was taken (for a refund). */
    public List<ItemStack> take(Player player, Map<String, Integer> amounts) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> taken = new ArrayList<>();
        for (Map.Entry<String, Integer> e : amounts.entrySet()) {
            int left = e.getValue();
            for (int slot = 0; slot < 36 && left > 0; slot++) {
                ItemStack s = inv.getItem(slot);
                if (!matches(s, e.getKey())) continue;
                int n = Math.min(left, s.getAmount());
                ItemStack part = s.clone();
                part.setAmount(n);
                taken.add(part);
                if (n == s.getAmount()) inv.setItem(slot, null); else s.setAmount(s.getAmount() - n);
                left -= n;
            }
            if (left > 0) throw new IllegalStateException("not enough " + e.getKey());
        }
        return taken;
    }

    /** Gives stacks back; anything that does not fit drops at the player's feet (owned by them). */
    public void give(Player player, List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        Map<Integer, ItemStack> rest = player.getInventory().addItem(stacks.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        for (ItemStack s : rest.values()) {
            var drop = player.getWorld().dropItem(player.getLocation(), s);
            drop.setOwner(player.getUniqueId());
            drop.setPickupDelay(0);
        }
    }

    /** True if all stacks fit into the main inventory right now. */
    public boolean fits(Player player, List<ItemStack> stacks) {
        var sim = Bukkit.createInventory(null, 36);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < 36; i++) if (storage[i] != null) sim.setItem(i, storage[i].clone());
        return sim.addItem(stacks.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).isEmpty();
    }

    /** Display name of an item id (content display name or prettified vanilla id). */
    public String displayName(String itemId) {
        var def = registry.getItem(itemId);
        if (def != null) return def.getDisplay();
        String s = itemId.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A display stack of an item id (content item or vanilla). */
    public ItemStack displayStack(String itemId, int amount) {
        if (registry.hasItem(itemId)) return registry.createItemStack(itemId, amount);
        Material m = Material.matchMaterial(itemId.toUpperCase(Locale.ROOT));
        return new ItemStack(m != null && m.isItem() ? m : Material.BARRIER, Math.max(1, Math.min(amount, 99)));
    }

    /** Saves the inventory soon after a change so the DB snapshot matches the committed operation. */
    public void persist(Player player) {
        if (plugin.getInventorySync() != null) plugin.getInventorySync().snapshotAndMaybeSave(player);
    }

    // ------------------------------------------------------------------ database (async)

    /** Loads the player's ENERGY balance off the main thread, then calls back on the main thread. */
    public void loadEnergy(Player player, Consumer<Long> callback) {
        loadBalance(player, "ENERGY", callback);
    }

    /** Wallet balance of a currency (-1 if it could not be read), delivered on the main thread. */
    public void loadBalance(Player player, String currency, Consumer<Long> callback) {
        UUID mc = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long energy = -1;
            try {
                UUID account = plugin.getDatabaseManager().getAccountId(mc);
                if (account != null) energy = plugin.getDatabaseManager().getBalances(account).getOrDefault(currency, 0L);
            } catch (SQLException e) {
                plugin.getLogger().warning("[Craft] Could not load energy of " + mc + ": " + e.getMessage());
            }
            long result = energy;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /** Runs {@link #commitNow} on an async thread and hands the result back on the main thread. */
    public void commit(Commit c, Consumer<Result> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result r = commitNow(c);
            Bukkit.getScheduler().runTask(plugin, () -> {
                var prog = plugin.getProgression();
                if (prog != null && "ENERGY".equals(c.currency()) && r.ok()) {
                    prog.noteEnergy(c.minecraftId(), r.energyAfter());
                    prog.onEnergySpent(c.minecraftId(), c.energy()); // Server XP for crafting / forging
                }
                callback.accept(r);
            });
        });
    }

    /** Async thread only. One transaction: operation + energy ledger + item_transactions. */
    public Result commitNow(Commit c) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            UUID account = plugin.getDatabaseManager().getAccountId(c.minecraftId());
            if (account == null) return new Result(Status.ERROR, -1);
            conn.setAutoCommit(false);
            try {
                UUID opId = UUID.randomUUID();
                if (!Ledger.insertOperation(conn, opId, account, c.idempotencyKey(), c.requestHash(), c.commandType(),
                        c.response() == null ? "{}" : c.response().toString())) {
                    conn.rollback();
                    return new Result(Status.DUPLICATE, -1);
                }
                Ledger.Wallet wallet = Ledger.lockWallet(conn, account, null, c.currency());
                if (wallet.balance() < c.energy()) {
                    conn.rollback();
                    return new Result(Status.NO_ENERGY, wallet.balance());
                }
                if (c.energy() > 0) {
                    Ledger.Wallet fees = Ledger.lockWallet(conn, null, FEE_ACCOUNT, c.currency());
                    Ledger.transfer(conn, opId, wallet.id(), fees.id(), c.energy(), c.commandType());
                }
                try (PreparedStatement tx = conn.prepareStatement(
                        "INSERT INTO item_transactions (operation_id, item_id, definition_id, from_actor_id, to_actor_id, source_action, metadata)"
                                + " VALUES (?, NULL, ?, NULL, ?, ?, ?::jsonb)")) {
                    for (ItemTx t : c.transactions()) {
                        tx.setObject(1, opId);
                        tx.setString(2, t.definitionId());
                        tx.setObject(3, account);
                        tx.setString(4, t.action());
                        tx.setString(5, t.metadata().toString());
                        tx.addBatch();
                    }
                    if (!c.transactions().isEmpty()) tx.executeBatch();
                }
                conn.commit();
                return new Result(Status.OK, wallet.balance() - c.energy());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Craft] " + c.commandType() + " " + c.idempotencyKey() + " failed for " + c.minecraftId() + ": " + e.getMessage());
            return new Result(Status.ERROR, -1);
        }
    }

    /** Async thread only. Admin top-up: ADMIN_GRANTS → player, double entry. Returns the new balance or -1. */
    public long grantEnergyNow(UUID minecraftId, long amount, String adminName) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            UUID account = plugin.getDatabaseManager().getAccountId(minecraftId);
            if (account == null) return -1;
            conn.setAutoCommit(false);
            try {
                UUID opId = UUID.randomUUID();
                JsonObject resp = new JsonObject();
                resp.addProperty("amount", amount);
                resp.addProperty("by", adminName);
                Ledger.insertOperation(conn, opId, account, "admin_energy/" + opId, "ENERGY:" + amount, "ADMIN_ENERGY", resp.toString());
                Ledger.Wallet wallet = Ledger.lockWallet(conn, account, null, "ENERGY");
                Ledger.Wallet admin = Ledger.lockWallet(conn, null, ADMIN_ACCOUNT, "ENERGY");
                Ledger.transfer(conn, opId, admin.id(), wallet.id(), amount, "ADMIN_ENERGY");
                conn.commit();
                return wallet.balance() + amount;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Craft] Energy grant to " + minecraftId + " failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Returns stacks to a player who went offline while an action was in flight: they go to the
     * /grants mailbox (as item ids — gear loses its Advancement/Refine there, so this is logged).
     */
    public void returnOffline(UUID minecraftId, List<ItemStack> stacks, String type) {
        if (stacks.isEmpty()) return;
        if (stacks.stream().anyMatch(s -> gear.read(s) != null)) {
            plugin.getLogger().warning("[Craft] " + minecraftId + " left during " + type + "; gear returned via mailbox without its upgrades: "
                    + stacks.stream().map(s -> String.valueOf(gear.read(s))).toList());
        }
        String payload = com.dimensionalminer.core.dimensionalMinerCore.mining.RewardItems.mailboxPayload(registry, stacks, type);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement g = conn.prepareStatement(
                         "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE')")) {
                UUID account = plugin.getDatabaseManager().getAccountId(minecraftId);
                UUID grant = UUID.randomUUID();
                g.setObject(1, grant);
                g.setObject(2, account);
                g.setString(3, "craft_return:" + grant);
                g.setString(4, payload);
                g.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("[Craft] Could not mail items back to " + minecraftId + ": " + e.getMessage() + " payload=" + payload);
            }
        });
    }

    public static Map<String, Integer> amounts(List<Recipe.Stack> stacks) {
        Map<String, Integer> m = new HashMap<>();
        for (Recipe.Stack s : stacks) m.merge(s.item(), s.qty(), Integer::sum);
        return m;
    }
}
