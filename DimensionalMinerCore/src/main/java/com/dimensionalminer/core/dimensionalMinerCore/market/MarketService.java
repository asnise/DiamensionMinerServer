package com.dimensionalminer.core.dimensionalMinerCore.market;

import com.dimensionalminer.core.dimensionalMinerCore.DimensionalMinerCore;
import com.dimensionalminer.core.dimensionalMinerCore.economy.Ledger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auction House (8.4) and the COIN economy around it (8.3). Fixed-price listings, GDD rules:
 * <ul>
 *   <li>List: the exact ItemStack goes into escrow (asset_locations MARKET_ESCROW + material_stacks.item_data)
 *       with the listing, in one transaction, after it was taken from the seller's hand.</li>
 *   <li>Buy (one transaction): listing ACTIVE and not expired (row lock) → buyer pays → seller gets the price
 *       minus tax → tax to {@value #TAX_ACCOUNT} → item goes to the buyer's /grants mailbox → SOLD.</li>
 *   <li>Cancel / expire: the item goes back to the seller's mailbox.</li>
 * </ul>
 * Every method blocks on the DB: async threads only.
 */
public final class MarketService {

    public static final String TAX_ACCOUNT = "MARKET_TAX";
    public static final String BUYBACK_ACCOUNT = "MERCHANT_BUYBACK";

    public record Listing(UUID id, UUID sellerAccount, String sellerName, String definitionId, int quantity,
                          long price, long expiresAtMillis, byte[] itemData) {
        public ItemStack item() { return ItemStack.deserializeBytes(itemData); }
    }

    public enum Status { OK, NOT_FOUND, OWN_LISTING, NO_FUNDS, LIMIT, DUPLICATE, ERROR }

    public record BuyResult(Status status, long price, long balance) {}

    private final DimensionalMinerCore plugin;
    final int taxBasisPoints;
    final int listingHours;
    final int maxListings;
    final long minPrice, maxPrice;

    public MarketService(DimensionalMinerCore plugin) {
        this.plugin = plugin;
        var c = plugin.getConfig();
        this.taxBasisPoints = Math.clamp(c.getInt("economy.market.tax_basis_points", 500), 0, 10_000);
        this.listingHours = Math.max(1, c.getInt("economy.market.listing_hours", 48));
        this.maxListings = Math.max(1, c.getInt("economy.market.max_listings_per_player", 10));
        this.minPrice = Math.max(1, c.getLong("economy.market.min_price", 1));
        this.maxPrice = Math.max(minPrice, c.getLong("economy.market.max_price", 10_000_000));
    }

    public long tax(long price) {
        return price * taxBasisPoints / 10_000; // floor (GDD: 5% floor)
    }

    private UUID account(UUID mc) throws SQLException {
        return plugin.getDatabaseManager().getAccountId(mc);
    }

    // ------------------------------------------------------------------ list

    /** The stack was already taken from the seller; on anything but OK the caller gives it back. */
    public Status list(UUID sellerMc, ItemStack stack, String definitionId, String binding, long price) {
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            UUID seller = account(sellerMc);
            c.setAutoCommit(false);
            try {
                try (PreparedStatement n = c.prepareStatement(
                        "SELECT COUNT(*) FROM market_listings WHERE seller_id = ? AND state = 'ACTIVE'")) {
                    n.setObject(1, seller);
                    try (ResultSet rs = n.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) >= maxListings) { c.rollback(); return Status.LIMIT; }
                    }
                }
                UUID listing = UUID.randomUUID(), location = UUID.randomUUID(), op = UUID.randomUUID();
                JsonObject resp = new JsonObject();
                resp.addProperty("listing_id", listing.toString());
                resp.addProperty("item", definitionId);
                resp.addProperty("qty", stack.getAmount());
                resp.addProperty("price", price);
                Ledger.insertOperation(c, op, seller, "market_list/" + listing, definitionId + "x" + stack.getAmount() + "@" + price,
                        "MARKET_LIST", resp.toString());
                try (PreparedStatement l = c.prepareStatement(
                        "INSERT INTO asset_locations (location_id, owner_id, kind, asset_kind, container_key) VALUES (?, ?, 'MARKET_ESCROW', 'STACK', ?)")) {
                    l.setObject(1, location);
                    l.setObject(2, seller);
                    l.setString(3, "market:" + listing);
                    l.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO material_stacks (stack_id, location_id, definition_id, quantity, binding, item_data) VALUES (?, ?, ?, ?, ?, ?)")) {
                    s.setObject(1, UUID.randomUUID());
                    s.setObject(2, location);
                    s.setString(3, definitionId);
                    s.setLong(4, stack.getAmount());
                    s.setString(5, binding);
                    s.setBytes(6, stack.serializeAsBytes());
                    s.executeUpdate();
                }
                try (PreparedStatement m = c.prepareStatement(
                        "INSERT INTO market_listings (listing_id, seller_id, escrow_location_id, gross_price, tax_basis_points, state, expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', now() + make_interval(hours => ?))")) {
                    m.setObject(1, listing);
                    m.setObject(2, seller);
                    m.setObject(3, location);
                    m.setLong(4, price);
                    m.setInt(5, taxBasisPoints);
                    m.setInt(6, listingHours);
                    m.executeUpdate();
                }
                itemTx(c, op, definitionId, seller, null, "MARKET_LIST", stack.getAmount(), listing);
                c.commit();
                return Status.OK;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Market] List by " + sellerMc + " failed: " + e.getMessage());
            return Status.ERROR;
        }
    }

    // ------------------------------------------------------------------ browse

    /** Active, unexpired listings, newest first. {@code sellerMc} != null = only that seller's. */
    public List<Listing> browse(UUID sellerMc, String search, int offset, int limit) throws SQLException {
        List<Listing> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT ml.listing_id, ml.seller_id, ua.username, ms.definition_id, ms.quantity, ml.gross_price, ml.expires_at, ms.item_data"
                        + " FROM market_listings ml JOIN material_stacks ms ON ms.location_id = ml.escrow_location_id"
                        + " JOIN user_accounts ua ON ua.account_id = ml.seller_id"
                        + " WHERE ml.state = 'ACTIVE' AND ml.expires_at > now()");
        if (sellerMc != null) sql.append(" AND ml.seller_id = ?");
        if (search != null && !search.isBlank()) sql.append(" AND ms.definition_id ILIKE ?");
        sql.append(" ORDER BY ml.created_at DESC, ml.listing_id LIMIT ? OFFSET ?");
        try (Connection c = plugin.getDatabaseManager().getConnection(); PreparedStatement p = c.prepareStatement(sql.toString())) {
            int i = 1;
            if (sellerMc != null) p.setObject(i++, account(sellerMc));
            if (search != null && !search.isBlank()) p.setString(i++, "%" + search.replace("%", "") + "%");
            p.setInt(i++, limit);
            p.setInt(i, offset);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(new Listing((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getLong(6), rs.getTimestamp(7).getTime(), rs.getBytes(8)));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ buy

    public BuyResult buy(UUID buyerMc, UUID listingId, long expectedPrice) {
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            UUID buyer = account(buyerMc);
            c.setAutoCommit(false);
            try {
                UUID seller, location;
                long price;
                String defId;
                int qty;
                byte[] data;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT ml.seller_id, ml.escrow_location_id, ml.gross_price, ms.definition_id, ms.quantity, ms.item_data"
                                + " FROM market_listings ml JOIN material_stacks ms ON ms.location_id = ml.escrow_location_id"
                                + " WHERE ml.listing_id = ? AND ml.state = 'ACTIVE' AND ml.expires_at > now() FOR UPDATE OF ml")) {
                    sel.setObject(1, listingId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new BuyResult(Status.NOT_FOUND, 0, -1); }
                        seller = (UUID) rs.getObject(1);
                        location = (UUID) rs.getObject(2);
                        price = rs.getLong(3);
                        defId = rs.getString(4);
                        qty = rs.getInt(5);
                        data = rs.getBytes(6);
                    }
                }
                if (seller.equals(buyer)) { c.rollback(); return new BuyResult(Status.OWN_LISTING, price, -1); }
                if (price != expectedPrice) { c.rollback(); return new BuyResult(Status.NOT_FOUND, price, -1); }

                UUID op = UUID.randomUUID();
                JsonObject resp = new JsonObject();
                resp.addProperty("listing_id", listingId.toString());
                resp.addProperty("price", price);
                resp.addProperty("tax", tax(price));
                if (!Ledger.insertOperation(c, op, buyer, "market_buy/" + listingId, String.valueOf(price), "MARKET_BUY", resp.toString())) {
                    c.rollback();
                    return new BuyResult(Status.DUPLICATE, price, -1);
                }
                // Lock both player wallets in a fixed order (no deadlock between two crossing purchases).
                Ledger.Wallet buyerW, sellerW;
                if (buyer.toString().compareTo(seller.toString()) < 0) {
                    buyerW = Ledger.lockWallet(c, buyer, null, "COIN");
                    sellerW = Ledger.lockWallet(c, seller, null, "COIN");
                } else {
                    sellerW = Ledger.lockWallet(c, seller, null, "COIN");
                    buyerW = Ledger.lockWallet(c, buyer, null, "COIN");
                }
                if (buyerW.balance() < price) { c.rollback(); return new BuyResult(Status.NO_FUNDS, price, buyerW.balance()); }
                long tax = tax(price);
                // One entry per wallet per operation (uq_ledger_operation_account): buyer −price,
                // seller +(price − tax), treasury +tax.
                Ledger.applyDelta(c, op, buyerW.id(), -price, "MARKET_BUY");
                if (price - tax > 0) Ledger.applyDelta(c, op, sellerW.id(), price - tax, "MARKET_SALE");
                if (tax > 0) {
                    Ledger.Wallet taxW = Ledger.lockWallet(c, null, TAX_ACCOUNT, "COIN");
                    Ledger.applyDelta(c, op, taxW.id(), tax, "MARKET_TAX");
                }
                UUID grant = mailExact(c, buyer, "market_buy:" + listingId, "MARKET_PURCHASE", defId, qty, data);
                moveToGrant(c, location, buyer, grant);
                try (PreparedStatement u = c.prepareStatement(
                        "UPDATE market_listings SET state = 'SOLD', buyer_id = ?, buyer_grant_id = ?, version = version + 1 WHERE listing_id = ?")) {
                    u.setObject(1, buyer);
                    u.setObject(2, grant);
                    u.setObject(3, listingId);
                    u.executeUpdate();
                }
                itemTx(c, op, defId, seller, buyer, "MARKET_BUY", qty, listingId);
                c.commit();
                return new BuyResult(Status.OK, price, buyerW.balance() - price);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Market] Buy of " + listingId + " by " + buyerMc + " failed: " + e.getMessage());
            return new BuyResult(Status.ERROR, 0, -1);
        }
    }

    // ------------------------------------------------------------------ cancel / expire

    /** Seller takes a listing down; the item goes to their mailbox. */
    public Status cancel(UUID sellerMc, UUID listingId) {
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            UUID seller = account(sellerMc);
            return close(c, listingId, seller, "CANCELLED") ? Status.OK : Status.NOT_FOUND;
        } catch (SQLException e) {
            plugin.getLogger().severe("[Market] Cancel of " + listingId + " failed: " + e.getMessage());
            return Status.ERROR;
        }
    }

    /** Listings past expires_at → EXPIRED, item back to the seller's mailbox. Returns how many. */
    public int expireDue() {
        int n = 0;
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            List<UUID[]> due = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT listing_id, seller_id FROM market_listings WHERE state = 'ACTIVE' AND expires_at <= now() LIMIT 200")) {
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) due.add(new UUID[]{(UUID) rs.getObject(1), (UUID) rs.getObject(2)});
                }
            }
            for (UUID[] d : due) if (close(c, d[0], d[1], "EXPIRED")) n++;
        } catch (SQLException e) {
            plugin.getLogger().warning("[Market] Expiry sweep failed: " + e.getMessage());
        }
        return n;
    }

    /** One transaction: ACTIVE → state, escrow → seller's mailbox. */
    private boolean close(Connection c, UUID listingId, UUID seller, String state) throws SQLException {
        c.setAutoCommit(false);
        try {
            UUID location;
            String defId;
            int qty;
            byte[] data;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT ml.escrow_location_id, ms.definition_id, ms.quantity, ms.item_data FROM market_listings ml"
                            + " JOIN material_stacks ms ON ms.location_id = ml.escrow_location_id"
                            + " WHERE ml.listing_id = ? AND ml.seller_id = ? AND ml.state = 'ACTIVE' FOR UPDATE OF ml")) {
                sel.setObject(1, listingId);
                sel.setObject(2, seller);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) { c.rollback(); return false; }
                    location = (UUID) rs.getObject(1);
                    defId = rs.getString(2);
                    qty = rs.getInt(3);
                    data = rs.getBytes(4);
                }
            }
            UUID op = UUID.randomUUID();
            Ledger.insertOperation(c, op, seller, "market_" + state.toLowerCase() + "/" + listingId, listingId.toString(),
                    "MARKET_" + state, "{}");
            UUID grant = mailExact(c, seller, "market_return:" + listingId, "MARKET_RETURN", defId, qty, data);
            moveToGrant(c, location, seller, grant);
            try (PreparedStatement u = c.prepareStatement("UPDATE market_listings SET state = ?, version = version + 1 WHERE listing_id = ?")) {
                u.setString(1, state);
                u.setObject(2, listingId);
                u.executeUpdate();
            }
            itemTx(c, op, defId, null, seller, "MARKET_" + state, qty, listingId);
            c.commit();
            return true;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ merchant buyback (8.3 coin source)

    /** COIN a player got from the merchant today (server time zone day). */
    public long buybackToday(UUID mc) throws SQLException {
        try (Connection c = plugin.getDatabaseManager().getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT COALESCE(SUM(le.delta), 0) FROM ledger_entries le JOIN accounts a ON a.account_id = le.account_id"
                        + " WHERE a.owner_id = ? AND a.currency = 'COIN' AND le.reason = 'BUYBACK' AND le.created_at >= date_trunc('day', now())")) {
            p.setObject(1, account(mc));
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Pays {@code coin} for items the caller already took (returns false → caller gives them back).
     * The daily cap is checked again inside the transaction.
     */
    public boolean buyback(UUID mc, String definitionId, int qty, long coin, long dailyCap) {
        try (Connection c = plugin.getDatabaseManager().getConnection()) {
            UUID acc = account(mc);
            c.setAutoCommit(false);
            try {
                Ledger.Wallet w = Ledger.lockWallet(c, acc, null, "COIN"); // serialises this player's buybacks
                long today;
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT COALESCE(SUM(delta), 0) FROM ledger_entries WHERE account_id = ? AND reason = 'BUYBACK' AND created_at >= date_trunc('day', now())")) {
                    p.setObject(1, w.id());
                    try (ResultSet rs = p.executeQuery()) {
                        rs.next();
                        today = rs.getLong(1);
                    }
                }
                if (today + coin > dailyCap) { c.rollback(); return false; }
                UUID op = UUID.randomUUID();
                Ledger.insertOperation(c, op, acc, "buyback/" + op, definitionId + "x" + qty, "MERCHANT_BUYBACK",
                        "{\"item\":\"" + definitionId + "\",\"qty\":" + qty + ",\"coin\":" + coin + "}");
                Ledger.Wallet src = Ledger.lockWallet(c, null, BUYBACK_ACCOUNT, "COIN");
                Ledger.transfer(c, op, src.id(), w.id(), coin, "BUYBACK");
                itemTx(c, op, definitionId, acc, null, "MERCHANT_BUYBACK", qty, null);
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[Market] Buyback for " + mc + " failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ economy stats (8.3 monitoring)

    /** reason → net COIN that went to players in the last {@code hours} (positive = source, negative = sink). */
    public Map<String, Long> coinFlows(int hours) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = plugin.getDatabaseManager().getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT le.reason, SUM(le.delta) FROM ledger_entries le JOIN accounts a ON a.account_id = le.account_id"
                        + " WHERE a.currency = 'COIN' AND a.owner_id IS NOT NULL AND le.created_at >= now() - make_interval(hours => ?)"
                        + " GROUP BY le.reason ORDER BY SUM(le.delta) DESC")) {
            p.setInt(1, hours);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    /** [total COIN held by players, number of player wallets with COIN > 0, active listings]. */
    public long[] totals() throws SQLException {
        try (Connection c = plugin.getDatabaseManager().getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT (SELECT COALESCE(SUM(balance),0) FROM accounts WHERE currency='COIN' AND owner_id IS NOT NULL),"
                        + " (SELECT COUNT(*) FROM accounts WHERE currency='COIN' AND owner_id IS NOT NULL AND balance > 0),"
                        + " (SELECT COUNT(*) FROM market_listings WHERE state='ACTIVE' AND expires_at > now())")) {
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)};
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Mailbox grant holding the exact item (item_data, base64) so gear keeps its upgrades. */
    static UUID mailExact(Connection c, UUID account, String sourceKey, String type, String defId, int qty, byte[] data) throws SQLException {
        JsonObject it = new JsonObject();
        it.addProperty("item_id", defId);
        it.addProperty("amount", qty);
        it.addProperty("item_data", Base64.getEncoder().encodeToString(data));
        JsonArray items = new JsonArray();
        items.add(it);
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.add("items", items);
        UUID grant = UUID.randomUUID();
        try (PreparedStatement g = c.prepareStatement(
                "INSERT INTO reward_grants (grant_id, player_id, source_key, payload, state) VALUES (?, ?, ?, ?::jsonb, 'AVAILABLE')")) {
            g.setObject(1, grant);
            g.setObject(2, account);
            g.setString(3, sourceKey);
            g.setString(4, payload.toString());
            g.executeUpdate();
        }
        return grant;
    }

    private static void moveToGrant(Connection c, UUID location, UUID newOwner, UUID grant) throws SQLException {
        try (PreparedStatement l = c.prepareStatement(
                "UPDATE asset_locations SET kind = 'GRANT', owner_id = ?, grant_id = ?, container_key = NULL, version = version + 1 WHERE location_id = ?")) {
            l.setObject(1, newOwner);
            l.setObject(2, grant);
            l.setObject(3, location);
            l.executeUpdate();
        }
    }

    static void itemTx(Connection c, UUID op, String defId, UUID from, UUID to, String action, int qty, UUID ref) throws SQLException {
        JsonObject meta = new JsonObject();
        meta.addProperty("amount", qty);
        if (ref != null) meta.addProperty("ref", ref.toString());
        try (PreparedStatement tx = c.prepareStatement(
                "INSERT INTO item_transactions (operation_id, item_id, definition_id, from_actor_id, to_actor_id, source_action, metadata)"
                        + " VALUES (?, NULL, ?, ?, ?, ?, ?::jsonb)")) {
            tx.setObject(1, op);
            tx.setString(2, defId);
            tx.setObject(3, from);
            tx.setObject(4, to);
            tx.setString(5, action);
            tx.setString(6, meta.toString());
            tx.executeUpdate();
        }
    }
}
