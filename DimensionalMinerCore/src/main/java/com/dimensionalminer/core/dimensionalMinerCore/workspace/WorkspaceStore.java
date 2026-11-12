package com.dimensionalminer.core.dimensionalMinerCore.workspace;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.craft.Recipe;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Database side of Workspaces (8.1) and processing jobs (8.2). Blocking: async threads only.
 * Tables: workspaces (layout JSONB = placed stations), workspace_acl, asset_locations STASH +
 * material_stacks (the stash chest), processing_jobs (+ reward_grants for outputs / refunds).
 */
public final class WorkspaceStore {

    public enum Perm { VISIT, USE_STATION, STORAGE }

    public record Workspace(UUID id, UUID owner, String ownerName, JsonObject layout) {}

    /** A job as shown in the station menu. */
    public record Job(UUID id, UUID player, String playerName, String recipeId, int pad, String state, long finishesAtMillis) {}

    private final DimensionalMinerCore plugin;

    public WorkspaceStore(DimensionalMinerCore plugin) {
        this.plugin = plugin;
    }

    private Connection conn() throws SQLException { return plugin.getDatabaseManager().getConnection(); }

    // ------------------------------------------------------------------ workspace + ACL

    /** The owner's workspace, created on first use (MVP: everyone gets one). */
    public Workspace ensure(UUID ownerAccount) throws SQLException {
        try (Connection c = conn()) {
            try (PreparedStatement i = c.prepareStatement(
                    "INSERT INTO workspaces (workspace_id, owner_id, size_tier, layout) VALUES (?, ?, 1, '{}') ON CONFLICT (owner_id) DO NOTHING")) {
                i.setObject(1, UUID.randomUUID());
                i.setObject(2, ownerAccount);
                i.executeUpdate();
            }
            return select(c, "w.owner_id = ?", ownerAccount);
        }
    }

    /** Workspace of the account whose username matches (case-insensitive), or null. */
    public Workspace byOwnerName(String name) throws SQLException {
        try (Connection c = conn()) {
            return select(c, "lower(u.username) = lower(?)", name);
        }
    }

    private static Workspace select(Connection c, String where, Object arg) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT w.workspace_id, w.owner_id, u.username, w.layout FROM workspaces w JOIN user_accounts u ON u.account_id = w.owner_id WHERE " + where)) {
            s.setObject(1, arg);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return null;
                String layout = rs.getString(4);
                return new Workspace((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3),
                        layout == null ? new JsonObject() : JsonParser.parseString(layout).getAsJsonObject());
            }
        }
    }

    public void saveLayout(UUID workspace, JsonObject layout) throws SQLException {
        try (Connection c = conn(); PreparedStatement u = c.prepareStatement(
                "UPDATE workspaces SET layout = ?::jsonb, layout_version = layout_version + 1 WHERE workspace_id = ?")) {
            u.setString(1, layout.toString());
            u.setObject(2, workspace);
            u.executeUpdate();
        }
    }

    /** account → permissions granted in this workspace. */
    public Map<UUID, Set<Perm>> acl(UUID workspace) throws SQLException {
        Map<UUID, Set<Perm>> out = new HashMap<>();
        try (Connection c = conn(); PreparedStatement s = c.prepareStatement(
                "SELECT player_id, permission FROM workspace_acl WHERE workspace_id = ?")) {
            s.setObject(1, workspace);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.computeIfAbsent((UUID) rs.getObject(1), k -> EnumSet.noneOf(Perm.class)).add(Perm.valueOf(rs.getString(2)));
            }
        }
        return out;
    }

    /** [account_id, username] of a player by name, or null. */
    public Object[] accountByName(String name) throws SQLException {
        try (Connection c = conn(); PreparedStatement s = c.prepareStatement(
                "SELECT account_id, username FROM user_accounts WHERE lower(username) = lower(?) LIMIT 1")) {
            s.setString(1, name);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? new Object[]{rs.getObject(1), rs.getString(2)} : null;
            }
        }
    }

    public Map<UUID, String> names(Set<UUID> accounts) throws SQLException {
        Map<UUID, String> out = new HashMap<>();
        if (accounts.isEmpty()) return out;
        try (Connection c = conn(); PreparedStatement s = c.prepareStatement("SELECT account_id, username FROM user_accounts WHERE account_id = ANY(?)")) {
            s.setArray(1, c.createArrayOf("uuid", accounts.toArray()));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.put((UUID) rs.getObject(1), rs.getString(2));
            }
        }
        return out;
    }

    public void setAcl(UUID workspace, UUID account, Set<Perm> grant, Set<Perm> revoke) throws SQLException {
        try (Connection c = conn()) {
            for (Perm p : grant) try (PreparedStatement i = c.prepareStatement(
                    "INSERT INTO workspace_acl (workspace_id, player_id, permission) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                i.setObject(1, workspace);
                i.setObject(2, account);
                i.setString(3, p.name());
                i.executeUpdate();
            }
            for (Perm p : revoke) try (PreparedStatement d = c.prepareStatement(
                    "DELETE FROM workspace_acl WHERE workspace_id = ? AND player_id = ? AND permission = ?")) {
                d.setObject(1, workspace);
                d.setObject(2, account);
                d.setString(3, p.name());
                d.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ stash

    private static String stashKey(UUID workspace) { return "ws:" + workspace; }

    /** slot → exact item. */
    public Map<Integer, ItemStack> loadStash(Workspace ws) throws SQLException {
        Map<Integer, ItemStack> out = new HashMap<>();
        try (Connection c = conn(); PreparedStatement s = c.prepareStatement(
                "SELECT l.slot, ms.item_data FROM asset_locations l JOIN material_stacks ms ON ms.location_id = l.location_id"
                        + " WHERE l.owner_id = ? AND l.kind = 'STASH' AND l.container_key = ?")) {
            s.setObject(1, ws.owner());
            s.setString(2, stashKey(ws.id()));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    byte[] data = rs.getBytes(2);
                    if (data != null) out.put(rs.getInt(1), ItemStack.deserializeBytes(data));
                }
            }
        }
        return out;
    }

    /** Replaces the whole stash in one transaction. ids: slot → definition id (for the web console). */
    public void saveStash(Workspace ws, Map<Integer, ItemStack> items, Map<Integer, String> ids) throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement d1 = c.prepareStatement(
                        "DELETE FROM material_stacks WHERE location_id IN (SELECT location_id FROM asset_locations WHERE owner_id = ? AND kind = 'STASH' AND container_key = ?)");
                     PreparedStatement d2 = c.prepareStatement("DELETE FROM asset_locations WHERE owner_id = ? AND kind = 'STASH' AND container_key = ?")) {
                    for (PreparedStatement d : new PreparedStatement[]{d1, d2}) {
                        d.setObject(1, ws.owner());
                        d.setString(2, stashKey(ws.id()));
                        d.executeUpdate();
                    }
                }
                try (PreparedStatement l = c.prepareStatement(
                        "INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key, slot) VALUES (?, ?, 'STASH', 'STACK', ?, ?)");
                     PreparedStatement m = c.prepareStatement(
                             "INSERT INTO material_stacks (stack_id, location_id, definition_id, quantity, binding, item_data) VALUES (?, ?, ?, ?, 'UNBOUND', ?)")) {
                    for (Map.Entry<Integer, ItemStack> e : items.entrySet()) {
                        UUID loc = UUID.randomUUID();
                        l.setObject(1, loc);
                        l.setObject(2, ws.owner());
                        l.setString(3, stashKey(ws.id()));
                        l.setInt(4, e.getKey());
                        l.addBatch();
                        m.setObject(1, UUID.randomUUID());
                        m.setObject(2, loc);
                        m.setString(3, ids.get(e.getKey()));
                        m.setLong(4, e.getValue().getAmount());
                        m.setBytes(5, e.getValue().serializeAsBytes());
                        m.addBatch();
                    }
                    if (!items.isEmpty()) {
                        l.executeBatch();
                        m.executeBatch();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ------------------------------------------------------------------ processing jobs (8.2)

    public List<Job> jobs(UUID workspace) throws SQLException {
        List<Job> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement s = c.prepareStatement(
                "SELECT j.job_id, j.player_id, u.username, j.recipe_id, (j.output_spec->>'pad')::int, j.state, j.finishes_at"
                        + " FROM processing_jobs j JOIN user_accounts u ON u.account_id = j.player_id"
                        + " WHERE j.workspace_id = ? AND j.state = 'RUNNING' ORDER BY j.finishes_at")) {
            s.setObject(1, workspace);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Job((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getString(6), rs.getTimestamp(7).getTime()));
            }
        }
        return out;
    }

    /** A player's jobs anywhere: RUNNING ones and the last few finished. */
    public List<Job> jobsOf(UUID account) throws SQLException {
        List<Job> out = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement s = c.prepareStatement(
                "SELECT j.job_id, j.player_id, '', j.recipe_id, (j.output_spec->>'pad')::int, j.state, j.finishes_at FROM processing_jobs j"
                        + " WHERE j.player_id = ? AND (j.state = 'RUNNING' OR j.finishes_at > now() - interval '1 day') ORDER BY j.finishes_at DESC LIMIT 20")) {
            s.setObject(1, account);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) out.add(new Job((UUID) rs.getObject(1), (UUID) rs.getObject(2), "", rs.getString(4),
                        rs.getInt(5), rs.getString(6), rs.getTimestamp(7).getTime()));
            }
        }
        return out;
    }

    public enum Start { OK, PAD_BUSY, ERROR }

    /**
     * One transaction: lock the workspace row, check the pad is free, operation + job (RUNNING, inputs
     * escrowed as exact items, outputs rolled now so a retry never re-rolls), item_transactions.
     * The inputs were already taken from the player; on anything but OK the caller gives them back.
     */
    public Start start(UUID workspace, UUID account, Recipe r, int pad, List<ItemStack> inputs, Map<ItemStack, String> ids) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement lock = c.prepareStatement("SELECT 1 FROM workspaces WHERE workspace_id = ? FOR UPDATE")) {
                    lock.setObject(1, workspace);
                    lock.executeQuery().close();
                }
                try (PreparedStatement busy = c.prepareStatement(
                        "SELECT 1 FROM processing_jobs WHERE workspace_id = ? AND state = 'RUNNING' AND (output_spec->>'pad')::int = ?")) {
                    busy.setObject(1, workspace);
                    busy.setInt(2, pad);
                    try (ResultSet rs = busy.executeQuery()) {
                        if (rs.next()) { c.rollback(); return Start.PAD_BUSY; }
                    }
                }
                UUID job = UUID.randomUUID(), op = UUID.randomUUID();
                JsonArray escrow = new JsonArray();
                for (ItemStack s : inputs) {
                    JsonObject it = new JsonObject();
                    it.addProperty("item_id", ids.get(s));
                    it.addProperty("amount", s.getAmount());
                    it.addProperty("item_data", Base64.getEncoder().encodeToString(s.serializeAsBytes()));
                    escrow.add(it);
                }
                JsonArray result = new JsonArray();
                for (Recipe.Output o : r.outputs()) {
                    if (o.chance() < 100 && ThreadLocalRandom.current().nextDouble(100) >= o.chance()) continue;
                    JsonObject it = new JsonObject();
                    it.addProperty("item_id", o.item());
                    it.addProperty("amount", o.qty());
                    result.add(it);
                }
                JsonObject spec = new JsonObject();
                spec.addProperty("pad", pad);
                spec.add("result", result);
                Ledger.insertOperation(c, op, account, "ws_job/" + job, r.id() + "@" + r.version(), "PROCESSING_START", spec.toString());
                try (PreparedStatement i = c.prepareStatement(
                        "INSERT INTO processing_jobs (job_id, player_id, workspace_id, recipe_id, recipe_version, operation_id, input_escrow, output_spec, state, started_at, finishes_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'RUNNING', now(), now() + make_interval(secs => ?))")) {
                    i.setObject(1, job);
                    i.setObject(2, account);
                    i.setObject(3, workspace);
                    i.setString(4, r.id());
                    i.setInt(5, Math.abs(r.version()) % 1_000_000_000 + 1);
                    i.setObject(6, op);
                    i.setString(7, escrow.toString());
                    i.setString(8, spec.toString());
                    i.setInt(9, Math.max(1, r.durationSeconds()));
                    i.executeUpdate();
                }
                try (PreparedStatement tx = c.prepareStatement(
                        "INSERT INTO item_transactions (operation_id, item_id, definition_id, from_actor_id, to_actor_id, source_action, metadata)"
                                + " VALUES (?, NULL, ?, ?, NULL, 'PROCESSING_INPUT', ?::jsonb)")) {
                    for (ItemStack s : inputs) {
                        tx.setObject(1, op);
                        tx.setString(2, ids.get(s));
                        tx.setObject(3, account);
                        tx.setString(4, "{\"amount\":" + s.getAmount() + ",\"job\":\"" + job + "\"}");
                        tx.addBatch();
                    }
                    if (!inputs.isEmpty()) tx.executeBatch();
                }
                c.commit();
                return Start.OK;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Workspace] Job start failed: " + e.getMessage());
            return Start.ERROR;
        }
    }

    /**
     * Worker (any node): jobs whose time is up get their output grant exactly once and turn READY.
     * {@code FOR UPDATE SKIP LOCKED} so two workers never take the same job. Returns [player account, recipe].
     */
    public List<String[]> completeDue() {
        List<String[]> done = new ArrayList<>();
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                List<Object[]> due = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT job_id, player_id, recipe_id, output_spec FROM processing_jobs WHERE state = 'RUNNING' AND finishes_at <= now()"
                                + " ORDER BY finishes_at LIMIT 50 FOR UPDATE SKIP LOCKED")) {
                    try (ResultSet rs = s.executeQuery()) {
                        while (rs.next()) due.add(new Object[]{rs.getObject(1), rs.getObject(2), rs.getString(3), rs.getString(4)});
                    }
                }
                for (Object[] d : due) {
                    UUID job = (UUID) d[0], player = (UUID) d[1];
                    JsonObject spec = JsonParser.parseString((String) d[3]).getAsJsonObject();
                    JsonArray result = spec.has("result") ? spec.getAsJsonArray("result") : new JsonArray();
                    UUID grant = null;
                    if (!result.isEmpty()) {
                        grant = UUID.randomUUID();
                        JsonObject payload = new JsonObject();
                        payload.addProperty("type", "PROCESSING_OUTPUT");
                        payload.addProperty("recipe_id", (String) d[2]);
                        payload.add("items", result);
                        insertGrant(c, grant, player, "job:" + job, payload);
                    }
                    try (PreparedStatement u = c.prepareStatement(
                            "UPDATE processing_jobs SET state = 'READY', output_grant_id = ? WHERE job_id = ?")) {
                        u.setObject(1, grant);
                        u.setObject(2, job);
                        u.executeUpdate();
                    }
                    done.add(new String[]{player.toString(), (String) d[2]});
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Workspace] Job worker failed: " + e.getMessage());
        }
        return done;
    }

    /** RUNNING → CANCELLED; the escrowed inputs go back (exact) to the job's player via /grants. */
    public boolean cancel(UUID job, UUID byAccount, UUID workspaceOwner) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                UUID player;
                String escrow;
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT player_id, input_escrow FROM processing_jobs WHERE job_id = ? AND state = 'RUNNING' FOR UPDATE")) {
                    s.setObject(1, job);
                    try (ResultSet rs = s.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        player = (UUID) rs.getObject(1);
                        escrow = rs.getString(2);
                    }
                }
                if (!player.equals(byAccount) && !workspaceOwner.equals(byAccount)) { c.rollback(); return false; }
                JsonArray items = JsonParser.parseString(escrow).getAsJsonArray();
                if (!items.isEmpty()) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "PROCESSING_CANCELLED");
                    payload.add("items", items);
                    insertGrant(c, UUID.randomUUID(), player, "job_cancel:" + job, payload);
                }
                try (PreparedStatement u = c.prepareStatement("UPDATE processing_jobs SET state = 'CANCELLED' WHERE job_id = ?")) {
                    u.setObject(1, job);
                    u.executeUpdate();
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
            plugin.getLogger().severe("[Workspace] Job cancel failed: " + e.getMessage());
            return false;
        }
    }

    private static void insertGrant(Connection c, UUID grant, UUID player, String key, JsonObject payload) throws SQLException {
        try (PreparedStatement g = c.prepareStatement(
                "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE')")) {
            g.setObject(1, grant);
            g.setObject(2, player);
            g.setString(3, key);
            g.setString(4, payload.toString());
            g.executeUpdate();
        }
    }

    static int padOf(JsonElement e) {
        try { return e.getAsInt(); } catch (Exception ex) { return -1; }
    }
}
