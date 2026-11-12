package com.dimensionalminer.core.dimensionalMinerCore.dungeon.runtime;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.dungeon.DungeonDefinition;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Database side of a dungeon run (6.1) and its entry cost (6.2). Every method blocks on the DB:
 * async threads only (or onDisable, where schedulers no longer run).
 *
 * <p>Lifecycle in {@code instance_runs}: RESERVED (cost escrowed) → LOADING (template copied) →
 * ACTIVE (cost CONSUMED) → SUCCEEDED (rewards recorded) → CLOSED with an outcome. A run that never
 * reached ACTIVE ends ABORTED with the cost REFUNDED.
 *
 * <p>The escrow is written into the run's operation ({@code operations.response}): the key items taken
 * from the leader's inventory and the currency moved to the {@value #ESCROW_ACCOUNT} system account.
 * So a crash between payment and start is refunded on the next start ({@link #recover}).
 */
public final class DungeonRunStore {

    /** System wallet that holds currency entry costs (escrow, then kept as a sink once consumed). */
    public static final String ESCROW_ACCOUNT = "DUNGEON_ENTRY";

    public enum Reserve { OK, NO_FUNDS, ERROR }

    /** {@code shortCurrency} names the currency the leader could not pay (NO_FUNDS). */
    public record ReserveResult(Reserve status, String shortCurrency, long balance) {}

    private final DimensionalMinerCore plugin;

    public DungeonRunStore(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    private Connection conn() throws SQLException {
        return plugin.getDatabaseManager().getConnection();
    }

    private UUID account(UUID minecraftId) throws SQLException {
        return plugin.getDatabaseManager().getAccountId(minecraftId);
    }

    /**
     * One transaction: the run's operation (holding the escrow), the currency debit leader →
     * {@value #ESCROW_ACCOUNT}, the instance_runs row (RESERVED), one run_members row per roster member,
     * and an item_transactions row per key item taken.
     *
     * @param keyItems item id → amount already taken from the leader's inventory
     */
    public ReserveResult reserve(UUID runId, DungeonDefinition def, UUID partyId, UUID leaderMc,
                                 Collection<UUID> rosterMc, Map<String, Integer> keyItems, Map<String, Long> currencies) {
        try (Connection c = conn()) {
            UUID leader = account(leaderMc);
            c.setAutoCommit(false);
            try {
                JsonObject escrow = new JsonObject();
                escrow.addProperty("dungeon_id", def.getDefinitionId());
                JsonArray items = new JsonArray();
                keyItems.forEach((id, n) -> {
                    JsonObject it = new JsonObject();
                    it.addProperty("item_id", id);
                    it.addProperty("amount", n);
                    items.add(it);
                });
                escrow.add("items", items);
                JsonObject cur = new JsonObject();
                currencies.forEach(cur::addProperty);
                escrow.add("currencies", cur);

                UUID opId = UUID.randomUUID();
                Ledger.insertOperation(c, opId, leader, "dungeon_run/" + runId, def.getDefinitionId() + "@" + def.getVersion(),
                        "DUNGEON_RUN", escrow.toString());

                for (Map.Entry<String, Long> e : currencies.entrySet()) {
                    Ledger.Wallet w = Ledger.lockWallet(c, leader, null, e.getKey());
                    if (w.balance() < e.getValue()) {
                        c.rollback();
                        return new ReserveResult(Reserve.NO_FUNDS, e.getKey(), w.balance());
                    }
                    Ledger.Wallet escrowWallet = Ledger.lockWallet(c, null, ESCROW_ACCOUNT, e.getKey());
                    Ledger.transfer(c, opId, w.id(), escrowWallet.id(), e.getValue(), "DUNGEON_ENTRY");
                }

                try (PreparedStatement run = c.prepareStatement(
                        "INSERT INTO instance_runs (run_id, requester_id, party_id, slot_id, boss_id, config_version, state, key_state, operation_id)"
                                + " VALUES (?, ?, (SELECT party_id FROM parties WHERE party_id = ?), NULL, ?, ?, 'RESERVED', 'RESERVED', ?)")) {
                    run.setObject(1, runId);
                    run.setObject(2, leader);
                    run.setObject(3, partyId);
                    run.setString(4, def.getDefinitionId());
                    run.setInt(5, def.getVersion());
                    run.setObject(6, opId);
                    run.executeUpdate();
                }
                try (PreparedStatement m = c.prepareStatement(
                        "INSERT INTO run_members (run_id, player_id, state) VALUES (?, ?, 'READY') ON CONFLICT DO NOTHING")) {
                    for (UUID mc : rosterMc) {
                        m.setObject(1, runId);
                        m.setObject(2, account(mc));
                        m.addBatch();
                    }
                    m.executeBatch();
                }
                if (!keyItems.isEmpty()) {
                    try (PreparedStatement tx = c.prepareStatement(
                            "INSERT INTO item_transactions (operation_id, item_id, definition_id, from_actor_id, to_actor_id, source_action, metadata)"
                                    + " VALUES (?, NULL, ?, ?, NULL, 'DUNGEON_KEY_ESCROW', ?::jsonb)")) {
                        for (Map.Entry<String, Integer> e : keyItems.entrySet()) {
                            JsonObject meta = new JsonObject();
                            meta.addProperty("amount", e.getValue());
                            meta.addProperty("run_id", runId.toString());
                            tx.setObject(1, opId);
                            tx.setString(2, e.getKey());
                            tx.setObject(3, leader);
                            tx.setString(4, meta.toString());
                            tx.addBatch();
                        }
                        tx.executeBatch();
                    }
                }
                c.commit();
                return new ReserveResult(Reserve.OK, null, 0);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Dungeon] Could not reserve run " + runId + " of " + def.getDefinitionId() + ": " + e.getMessage());
            return new ReserveResult(Reserve.ERROR, null, 0);
        }
    }

    /** Template copied, world loading. */
    public void markLoading(UUID runId) {
        update(runId, "UPDATE instance_runs SET state = 'LOADING' WHERE run_id = ? AND state = 'RESERVED'");
    }

    /** The party is inside: the entry cost is spent for good. */
    public void markActive(UUID runId) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                exec(c, runId, "UPDATE instance_runs SET state = 'ACTIVE', key_state = 'CONSUMED', started_at = now()"
                        + " WHERE run_id = ? AND state IN ('RESERVED','LOADING')");
                exec(c, runId, "UPDATE run_members SET state = 'ACTIVE' WHERE run_id = ? AND state = 'READY'");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] Could not mark run " + runId + " ACTIVE: " + e.getMessage());
        }
    }

    /** Goal reached and rewards recorded; {@code rewarded} = members who were paid (eligible). */
    public void markSucceeded(UUID runId, Collection<UUID> rewardedMc) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                exec(c, runId, "UPDATE instance_runs SET state = 'SUCCEEDED', outcome = 'SUCCEEDED' WHERE run_id = ? AND state = 'ACTIVE'");
                try (PreparedStatement m = c.prepareStatement("UPDATE run_members SET eligible = true WHERE run_id = ? AND player_id = ?")) {
                    for (UUID mc : rewardedMc) {
                        m.setObject(1, runId);
                        m.setObject(2, account(mc));
                        m.addBatch();
                    }
                    m.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] Could not mark run " + runId + " SUCCEEDED: " + e.getMessage());
        }
    }

    /**
     * Run is over and its world removed. outcome: SUCCEEDED, FAILED (time up / everyone left) or
     * ABORTED (admin destroy, server stop). A run that never got ACTIVE is handled by {@link #refund}.
     */
    public void close(UUID runId, String outcome) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement u = c.prepareStatement(
                        "UPDATE instance_runs SET state = 'CLOSED', outcome = COALESCE(outcome, ?), finished_at = now()"
                                + " WHERE run_id = ? AND state NOT IN ('CLOSED','ABORTED','QUARANTINED')")) {
                    u.setString(1, outcome);
                    u.setObject(2, runId);
                    u.executeUpdate();
                }
                exec(c, runId, "UPDATE run_members SET state = 'RETURNED' WHERE run_id = ?");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] Could not close run " + runId + ": " + e.getMessage());
        }
    }

    /** Late joiner (/dungeon join). */
    public void memberJoined(UUID runId, UUID mc) {
        try (Connection c = conn(); PreparedStatement m = c.prepareStatement(
                "INSERT INTO run_members (run_id, player_id, state) VALUES (?, ?, 'ACTIVE')"
                        + " ON CONFLICT (run_id, player_id) DO UPDATE SET state = 'ACTIVE'")) {
            m.setObject(1, runId);
            m.setObject(2, account(mc));
            m.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] run_members join " + runId + ": " + e.getMessage());
        }
    }

    /** /exitdungeon: out for good, never eligible. */
    public void memberLeft(UUID runId, UUID mc) {
        try (Connection c = conn(); PreparedStatement m = c.prepareStatement(
                "UPDATE run_members SET state = 'RETURNED', eligible = false WHERE run_id = ? AND player_id = ?")) {
            m.setObject(1, runId);
            m.setObject(2, account(mc));
            m.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] run_members leave " + runId + ": " + e.getMessage());
        }
    }

    /**
     * Refunds a run that never started (load failure, server stop before ACTIVE) and ends it ABORTED.
     * Only one caller can win (key_state RESERVED → REFUNDED). Currency goes back through the ledger;
     * key items go to the /grants mailbox when {@code mailItems}, otherwise the caller hands back the
     * stacks it is holding.
     *
     * @return true if this call refunded the run (the caller may then give the items back)
     */
    public boolean refund(UUID runId, boolean mailItems) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                UUID leader;
                JsonObject escrow;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT r.requester_id, o.response FROM instance_runs r JOIN operations o ON o.operation_id = r.operation_id"
                                + " WHERE r.run_id = ? AND r.key_state = 'RESERVED' AND r.state IN ('RESERVED','LOADING') FOR UPDATE OF r")) {
                    sel.setObject(1, runId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return false;
                        }
                        leader = (UUID) rs.getObject(1);
                        String json = rs.getString(2);
                        escrow = json == null ? new JsonObject() : JsonParser.parseString(json).getAsJsonObject();
                    }
                }
                exec(c, runId, "UPDATE instance_runs SET state = 'ABORTED', outcome = 'ABORTED', key_state = 'REFUNDED', finished_at = now() WHERE run_id = ?");
                exec(c, runId, "UPDATE run_members SET state = 'RETURNED' WHERE run_id = ?");

                UUID refundOp = UUID.randomUUID();
                Ledger.insertOperation(c, refundOp, leader, "dungeon_refund/" + runId, runId.toString(), "DUNGEON_REFUND", escrow.toString());
                if (escrow.has("currencies") && escrow.get("currencies").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : escrow.getAsJsonObject("currencies").entrySet()) {
                        long amount = e.getValue().getAsLong();
                        if (amount <= 0) continue;
                        Ledger.Wallet from = Ledger.lockWallet(c, null, ESCROW_ACCOUNT, e.getKey());
                        Ledger.Wallet to = Ledger.lockWallet(c, leader, null, e.getKey());
                        Ledger.transfer(c, refundOp, from.id(), to.id(), amount, "DUNGEON_REFUND");
                    }
                }
                JsonArray items = escrow.has("items") && escrow.get("items").isJsonArray() ? escrow.getAsJsonArray("items") : new JsonArray();
                if (mailItems && !items.isEmpty()) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "DUNGEON_KEY_REFUND");
                    payload.addProperty("dungeon_id", escrow.has("dungeon_id") ? escrow.get("dungeon_id").getAsString() : "");
                    payload.add("items", items);
                    try (PreparedStatement g = c.prepareStatement(
                            "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE')"
                                    + " ON CONFLICT (source_key) DO NOTHING")) {
                        g.setObject(1, UUID.randomUUID());
                        g.setObject(2, leader);
                        g.setString(3, "dungeon_refund:" + runId);
                        g.setString(4, payload.toString());
                        g.executeUpdate();
                    }
                }
                if (!items.isEmpty()) {
                    try (PreparedStatement tx = c.prepareStatement(
                            "INSERT INTO item_transactions (operation_id, item_id, definition_id, from_actor_id, to_actor_id, source_action, metadata)"
                                    + " VALUES (?, NULL, ?, NULL, ?, 'DUNGEON_KEY_REFUND', ?::jsonb)")) {
                        for (JsonElement el : items) {
                            JsonObject it = el.getAsJsonObject();
                            JsonObject meta = new JsonObject();
                            meta.add("amount", it.get("amount"));
                            meta.addProperty("run_id", runId.toString());
                            meta.addProperty("via", mailItems ? "mailbox" : "inventory");
                            tx.setObject(1, refundOp);
                            tx.setString(2, it.get("item_id").getAsString());
                            tx.setObject(3, leader);
                            tx.setString(4, meta.toString());
                            tx.addBatch();
                        }
                        tx.executeBatch();
                    }
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Dungeon] Refund of run " + runId + " failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Startup: runs left open by a crash or stop. Never started → refunded (items by mail);
     * started → closed as ABORTED with no refund (the cost was consumed when the party went in).
     */
    public void recover() {
        List<UUID> unstarted = new ArrayList<>();
        List<UUID> started = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement sel = c.prepareStatement(
                "SELECT run_id, key_state FROM instance_runs WHERE state NOT IN ('CLOSED','ABORTED','QUARANTINED')")) {
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    if ("RESERVED".equals(rs.getString(2))) unstarted.add(id); else started.add(id);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] Could not check open runs: " + e.getMessage());
            return;
        }
        for (UUID id : unstarted) {
            boolean ok = refund(id, true);
            plugin.getLogger().info("[Dungeon] Run " + id + " never started before the last stop: " + (ok ? "entry cost refunded (mailbox)." : "refund FAILED."));
        }
        for (UUID id : started) close(id, "ABORTED");
        if (!started.isEmpty()) plugin.getLogger().info("[Dungeon] Closed " + started.size() + " run(s) left open by the last stop (ABORTED, no refund).");
    }

    private void update(UUID runId, String sql) {
        try (Connection c = conn()) {
            exec(c, runId, sql);
        } catch (SQLException e) {
            plugin.getLogger().warning("[Dungeon] run " + runId + ": " + e.getMessage());
        }
    }

    private static void exec(Connection c, UUID runId, String sql) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, runId);
            p.executeUpdate();
        }
    }
}
