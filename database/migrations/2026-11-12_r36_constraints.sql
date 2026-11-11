-- Task.md R36: indexes and economy invariants for dimensional_game.
-- Idempotent: safe to run more than once. Also appended to schema_game.sql for fresh databases.

-- 1. Missing indexes on lookup / foreign-key columns ------------------------------------------
CREATE INDEX IF NOT EXISTS idx_ledger_operation      ON ledger_entries(operation_id);
CREATE INDEX IF NOT EXISTS idx_outbox_operation      ON outbox_events(operation_id);
CREATE INDEX IF NOT EXISTS idx_grants_claim_op       ON reward_grants(claim_operation_id) WHERE claim_operation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_locations_owner_kind  ON asset_locations(owner_id, kind);
CREATE INDEX IF NOT EXISTS idx_locations_grant       ON asset_locations(grant_id) WHERE grant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_market_seller         ON market_listings(seller_id);
CREATE INDEX IF NOT EXISTS idx_audit_target          ON audit_log(target_id);
CREATE INDEX IF NOT EXISTS idx_item_tx_operation     ON item_transactions(operation_id);

-- 2. One ledger row per (operation, wallet): a retried write cannot book the same leg twice ----
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_operation_account ON ledger_entries(operation_id, account_id);

-- 3. Double-entry: every operation's ledger rows must sum to zero at COMMIT --------------------
-- Deferred, so both legs can be written by separate statements in the same transaction.
-- Only rows inserted from now on are checked; 4 single-entry operations written before the
-- R28 fix (web shop / admin grant, 2026-11) stay as history.
CREATE OR REPLACE FUNCTION ledger_operation_must_balance() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE total BIGINT;
BEGIN
    SELECT COALESCE(SUM(delta), 0) INTO total FROM ledger_entries WHERE operation_id = NEW.operation_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'ledger operation % does not balance (sum of deltas = %)', NEW.operation_id, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS ledger_balanced ON ledger_entries;
CREATE CONSTRAINT TRIGGER ledger_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_operation_must_balance();

-- 4. Deleting an item must not erase its history -------------------------------------------
ALTER TABLE item_transactions DROP CONSTRAINT IF EXISTS item_transactions_item_id_fkey;
ALTER TABLE item_transactions ADD CONSTRAINT item_transactions_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES items(item_id) ON DELETE SET NULL;

-- 5. Range checks -------------------------------------------------------------------------------
ALTER TABLE asset_locations DROP CONSTRAINT IF EXISTS inventory_slot_range;
ALTER TABLE asset_locations ADD CONSTRAINT inventory_slot_range
    CHECK (kind <> 'INVENTORY' OR slot BETWEEN 0 AND 35);

ALTER TABLE workspace_generators DROP CONSTRAINT IF EXISTS generator_amount_non_negative;
ALTER TABLE workspace_generators ADD CONSTRAINT generator_amount_non_negative
    CHECK (accumulated_amount >= 0);

ALTER TABLE market_listings DROP CONSTRAINT IF EXISTS sold_listing_has_grant;
ALTER TABLE market_listings ADD CONSTRAINT sold_listing_has_grant
    CHECK (state <> 'SOLD' OR buyer_grant_id IS NOT NULL);
