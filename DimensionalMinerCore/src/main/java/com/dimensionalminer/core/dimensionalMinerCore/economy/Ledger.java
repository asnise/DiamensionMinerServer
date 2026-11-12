package com.dimensionalminer.core.dimensionalMinerCore.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Double-entry helpers for dimensional_game, used inside an open transaction. Every movement writes
 * two ledger_entries under one operation whose deltas sum to zero (checked by the deferred
 * ledger_balanced trigger). Same rules as web-console/lib/ledger.js.
 */
public final class Ledger {

    private Ledger() {}

    public record Wallet(UUID id, long balance) {}

    /** Returns (and locks FOR UPDATE) the wallet of an owner account or a system code, creating it if missing. */
    public static Wallet lockWallet(Connection conn, UUID ownerId, String systemCode, String currency) throws SQLException {
        if ((ownerId == null) == (systemCode == null)) throw new IllegalArgumentException("need exactly one of ownerId / systemCode");
        try (PreparedStatement ins = conn.prepareStatement(ownerId != null
                ? "INSERT INTO accounts (account_id, owner_id, currency, balance) VALUES (?, ?, ?, 0) ON CONFLICT (owner_id, currency) DO NOTHING"
                : "INSERT INTO accounts (account_id, system_code, currency, balance) VALUES (?, ?, ?, 0) ON CONFLICT (system_code, currency) DO NOTHING")) {
            ins.setObject(1, UUID.randomUUID());
            if (ownerId != null) ins.setObject(2, ownerId); else ins.setString(2, systemCode);
            ins.setString(3, currency);
            ins.executeUpdate();
        }
        try (PreparedStatement sel = conn.prepareStatement(ownerId != null
                ? "SELECT account_id, balance FROM accounts WHERE owner_id = ? AND currency = ? FOR UPDATE"
                : "SELECT account_id, balance FROM accounts WHERE system_code = ? AND currency = ? FOR UPDATE")) {
            if (ownerId != null) sel.setObject(1, ownerId); else sel.setString(1, systemCode);
            sel.setString(2, currency);
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) throw new SQLException("wallet not found after insert: " + currency);
                return new Wallet((UUID) rs.getObject(1), rs.getLong(2));
            }
        }
    }

    /** Moves amount (&gt; 0) between two wallets under one operation. */
    public static void transfer(Connection conn, UUID operationId, UUID fromWallet, UUID toWallet, long amount, String reason) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        applyDelta(conn, operationId, fromWallet, -amount, reason);
        applyDelta(conn, operationId, toWallet, amount, reason);
    }

    public static void applyDelta(Connection conn, UUID operationId, UUID walletId, long delta, String reason) throws SQLException {
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE accounts SET balance = balance + ?, version = version + 1 WHERE account_id = ?")) {
            upd.setLong(1, delta);
            upd.setObject(2, walletId);
            upd.executeUpdate();
        }
        try (PreparedStatement led = conn.prepareStatement(
                "INSERT INTO ledger_entries (entry_id, operation_id, account_id, delta, reason) VALUES (?, ?, ?, ?, ?)")) {
            led.setObject(1, UUID.randomUUID());
            led.setObject(2, operationId);
            led.setObject(3, walletId);
            led.setLong(4, delta);
            led.setString(5, reason);
            led.executeUpdate();
        }
    }

    /**
     * Inserts a SUCCEEDED operation. Returns false if (actor, idempotency key) already exists,
     * i.e. this request was already processed.
     */
    public static boolean insertOperation(Connection conn, UUID operationId, UUID actorId, String idempotencyKey,
                                          String requestHash, String commandType, String responseJson) throws SQLException {
        try (PreparedStatement op = conn.prepareStatement(
                "INSERT INTO operations (operation_id, actor_id, idempotency_key, request_hash, command_type, state, response, completed_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?::jsonb, now()) ON CONFLICT (actor_id, idempotency_key) DO NOTHING")) {
            op.setObject(1, operationId);
            op.setObject(2, actorId);
            op.setString(3, idempotencyKey);
            op.setString(4, requestHash);
            op.setString(5, commandType);
            op.setString(6, responseJson == null ? "{}" : responseJson);
            return op.executeUpdate() == 1;
        }
    }
}
