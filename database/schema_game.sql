-- =============================================================================
-- Dimensional Miner • Game Runtime Database Schema (dimensional_game)
-- =============================================================================
-- This database manages authoritative Game Server runtime state:
-- Accounts, Linked Identities, Economy (Wallets & Ledger), Inventory,
-- Asset Locations, Sessions, Instances, and Operations.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- 1. Identity & Account Layer
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_accounts (
    account_id UUID PRIMARY KEY,
    username TEXT NOT NULL,
    email TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','QUARANTINED','BANNED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS player_identities (
    identity_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    provider TEXT NOT NULL CHECK(provider IN ('MINECRAFT','DISCORD','WEB','CUSTOM')),
    identifier TEXT NOT NULL,
    display_name TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, identifier)
);

CREATE INDEX IF NOT EXISTS idx_identities_account ON player_identities(account_id);

-- Backward compatibility view for systems querying "players"
CREATE OR REPLACE VIEW players AS
SELECT 
    a.account_id AS player_id,
    a.username,
    a.created_at,
    a.status,
    i.identifier AS minecraft_uuid
FROM user_accounts a
LEFT JOIN player_identities i ON i.account_id = a.account_id AND i.provider = 'MINECRAFT';

-- -----------------------------------------------------------------------------
-- 2. Topology & Sessions
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodes (
    node_id TEXT PRIMARY KEY,
    role TEXT NOT NULL CHECK(role IN ('MAIN','INSTANCE','PROXY','WORKSPACE')),
    status TEXT NOT NULL CHECK(status IN ('READY','DRAINING','OFFLINE')),
    heartbeat_at TIMESTAMPTZ NOT NULL,
    capacity JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS player_sessions (
    player_id UUID PRIMARY KEY REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    node_id TEXT REFERENCES nodes(node_id),
    epoch BIGINT NOT NULL CHECK(epoch > 0),
    state TEXT NOT NULL CHECK(state IN ('ACTIVE','FROZEN','TRANSFERRING','OFFLINE')),
    lease_until TIMESTAMPTZ NOT NULL,
    snapshot_version BIGINT NOT NULL DEFAULT 0
);

-- -----------------------------------------------------------------------------
-- 3. Operations & Idempotency
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS operations (
    operation_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES user_accounts(account_id),
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    command_type TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PENDING','SUCCEEDED','REJECTED')),
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE(actor_id, idempotency_key)
);

-- -----------------------------------------------------------------------------
-- 4. Economy & Double-Entry Ledger
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounts (
    account_id UUID PRIMARY KEY,
    owner_id UUID REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    system_code TEXT,
    currency TEXT NOT NULL CHECK(currency IN ('COIN','ENERGY','SERVER_XP')),
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK((owner_id IS NULL) <> (system_code IS NULL)),
    CHECK(owner_id IS NULL OR balance >= 0),
    UNIQUE(owner_id, currency),
    UNIQUE(system_code, currency)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(operation_id),
    account_id UUID NOT NULL REFERENCES accounts(account_id),
    delta BIGINT NOT NULL CHECK(delta <> 0),
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ledger_account_time ON ledger_entries(account_id, created_at);

CREATE TABLE IF NOT EXISTS reward_grants (
    grant_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    source_key TEXT NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('AVAILABLE','CLAIMED','QUARANTINED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at TIMESTAMPTZ,
    claim_operation_id UUID REFERENCES operations(operation_id),
    CHECK((state = 'CLAIMED') = (claimed_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS mailbox_page ON reward_grants(player_id, created_at, grant_id) WHERE state = 'AVAILABLE';

-- -----------------------------------------------------------------------------
-- 5. Inventory & Assets (Decoupled from Content Definitions)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_locations (
    location_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK(kind IN ('INVENTORY','EQUIPPED','STASH','MARKET_ESCROW','JOB_ESCROW','GRANT','DESTROYED')),
    asset_kind TEXT NOT NULL CHECK(asset_kind IN ('ITEM','STACK')),
    container_key TEXT,
    slot INTEGER,
    grant_id UUID REFERENCES reward_grants(grant_id),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK(slot IS NULL OR slot >= 0),
    CHECK((kind IN ('INVENTORY','EQUIPPED','STASH')) = (slot IS NOT NULL)),
    CHECK(slot IS NULL OR container_key IS NOT NULL),
    CHECK((kind = 'GRANT') = (grant_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS occupied_slot ON asset_locations(owner_id, container_key, slot) WHERE slot IS NOT NULL;

CREATE TABLE IF NOT EXISTS items (
    item_id UUID PRIMARY KEY,
    location_id UUID NOT NULL UNIQUE REFERENCES asset_locations(location_id) ON DELETE CASCADE,
    definition_id TEXT NOT NULL,
    definition_version INTEGER NOT NULL DEFAULT 1,
    rarity TEXT NOT NULL,
    advancement INTEGER NOT NULL DEFAULT 0 CHECK(advancement BETWEEN 0 AND 100),
    refine INTEGER NOT NULL DEFAULT 0 CHECK(refine BETWEEN 0 AND 10),
    engraves JSONB NOT NULL DEFAULT '[]' CHECK(jsonb_typeof(engraves) = 'array' AND jsonb_array_length(engraves) <= 3),
    sockets JSONB NOT NULL DEFAULT '[]',
    binding TEXT NOT NULL CHECK(binding IN ('UNBOUND','ACCOUNT_BOUND')),
    durability INTEGER NOT NULL CHECK(durability >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    origin_source TEXT NOT NULL DEFAULT 'GENESIS',
    origin_actor_id UUID REFERENCES user_accounts(account_id),
    origin_context JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS material_stacks (
    stack_id UUID PRIMARY KEY,
    location_id UUID NOT NULL UNIQUE REFERENCES asset_locations(location_id) ON DELETE CASCADE,
    definition_id TEXT NOT NULL,
    definition_version INTEGER NOT NULL DEFAULT 1,
    quantity BIGINT NOT NULL CHECK(quantity > 0),
    binding TEXT NOT NULL CHECK(binding IN ('UNBOUND','ACCOUNT_BOUND')),
    -- Full Paper ItemStack (ItemStack#serializeAsBytes): enchants, durability, name, lore, PDC.
    -- NULL for rows written before 2026-11 (only definition_id + quantity were stored then).
    item_data BYTEA
);
-- Migration for databases created before item_data existed (safe to re-run).
ALTER TABLE material_stacks ADD COLUMN IF NOT EXISTS item_data BYTEA;

CREATE TABLE IF NOT EXISTS item_transactions (
    transaction_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id UUID REFERENCES operations(operation_id),
    item_id UUID REFERENCES items(item_id) ON DELETE CASCADE,
    definition_id TEXT NOT NULL,
    from_actor_id UUID REFERENCES user_accounts(account_id),
    to_actor_id UUID REFERENCES user_accounts(account_id),
    source_action TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_item_tx_item ON item_transactions(item_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_item_tx_actors ON item_transactions(from_actor_id, to_actor_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- 6. Progression & Gameplay Systems
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS player_unlocks (
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    unlock_id TEXT NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_operation_id UUID REFERENCES operations(operation_id),
    PRIMARY KEY(player_id, unlock_id)
);

CREATE TABLE IF NOT EXISTS quest_progress (
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    quest_id TEXT NOT NULL,
    definition_version INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('ACTIVE','COMPLETE','CLAIMED')),
    progress JSONB NOT NULL DEFAULT '{}',
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(player_id, quest_id)
);

CREATE TABLE IF NOT EXISTS transfers (
    transfer_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    source_node TEXT NOT NULL REFERENCES nodes(node_id),
    destination_node TEXT NOT NULL REFERENCES nodes(node_id),
    source_epoch BIGINT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK(state IN ('PREPARED','ACCEPTED','ACKED','CANCELLED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_epoch BIGINT,
    operation_id UUID NOT NULL REFERENCES operations(operation_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS one_open_transfer ON transfers(player_id) WHERE state IN ('PREPARED','ACCEPTED');

CREATE TABLE IF NOT EXISTS mine_nodes (
    channel_id TEXT NOT NULL REFERENCES nodes(node_id),
    node_id TEXT NOT NULL,
    dimension_id TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    generation BIGINT NOT NULL CHECK(generation >= 0),
    state TEXT NOT NULL CHECK(state IN ('READY','DEPLETED')),
    respawn_at TIMESTAMPTZ,
    ore_definition_id TEXT NOT NULL,
    ore_version INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY(channel_id, node_id),
    UNIQUE(channel_id, dimension_id, block_x, block_y, block_z)
);

CREATE INDEX IF NOT EXISTS due_mine_nodes ON mine_nodes(channel_id, respawn_at) WHERE state = 'DEPLETED';

CREATE TABLE IF NOT EXISTS parties (
    party_id UUID PRIMARY KEY,
    leader_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0,
    state TEXT NOT NULL CHECK(state IN ('OPEN','LOCKED','DISBANDED'))
);

CREATE TABLE IF NOT EXISTS party_members (
    party_id UUID NOT NULL REFERENCES parties(party_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    ready BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY(party_id, player_id),
    UNIQUE(player_id)
);

CREATE TABLE IF NOT EXISTS instance_slots (
    slot_id UUID PRIMARY KEY,
    node_id TEXT NOT NULL REFERENCES nodes(node_id),
    template_id TEXT NOT NULL,
    template_version INTEGER NOT NULL,
    world_key TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('WARM','RESERVED','ACTIVE','CLEANING','QUARANTINED')),
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(node_id, world_key)
);

CREATE TABLE IF NOT EXISTS instance_runs (
    run_id UUID PRIMARY KEY,
    requester_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    party_id UUID REFERENCES parties(party_id),
    slot_id UUID REFERENCES instance_slots(slot_id),
    boss_id TEXT NOT NULL,
    config_version INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('REQUESTED','RESERVED','LOADING','READY','ACTIVE','SUCCEEDED','FAILED','RETURNING','CLEANING','CLOSED','ABORTED','QUARANTINED')),
    outcome TEXT CHECK(outcome IN ('SUCCEEDED','FAILED','ABORTED')),
    key_state TEXT NOT NULL CHECK(key_state IN ('RESERVED','CONSUMED','REFUNDED')),
    key_escrow_location_id UUID REFERENCES asset_locations(location_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    operation_id UUID NOT NULL UNIQUE REFERENCES operations(operation_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS occupied_instance_slot ON instance_runs(slot_id) WHERE state NOT IN ('CLOSED','ABORTED');

CREATE TABLE IF NOT EXISTS run_members (
    run_id UUID NOT NULL REFERENCES instance_runs(run_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK(state IN ('INVITED','READY','ACTIVE','DOWNED','DISCONNECTED','RETURNED')),
    eligible BOOLEAN NOT NULL DEFAULT false,
    reconnect_until TIMESTAMPTZ,
    PRIMARY KEY(run_id, player_id)
);

CREATE TABLE IF NOT EXISTS run_rewards (
    run_id UUID NOT NULL REFERENCES instance_runs(run_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    reward_slot TEXT NOT NULL,
    grant_id UUID NOT NULL UNIQUE REFERENCES reward_grants(grant_id),
    PRIMARY KEY(run_id, player_id, reward_slot)
);

CREATE TABLE IF NOT EXISTS world_encounters (
    encounter_id UUID PRIMARY KEY,
    channel_id TEXT NOT NULL REFERENCES nodes(node_id),
    boss_type TEXT NOT NULL,
    schedule_window TEXT NOT NULL,
    config_version INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('SPAWNED','ACTIVE','FINISHED','ABORTED')),
    last_hit_player_id UUID REFERENCES user_accounts(account_id),
    outcome JSONB,
    UNIQUE(channel_id, boss_type, schedule_window)
);

CREATE TABLE IF NOT EXISTS encounter_contributions (
    encounter_id UUID NOT NULL REFERENCES world_encounters(encounter_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    damage_units BIGINT NOT NULL DEFAULT 0 CHECK(damage_units >= 0),
    support_units BIGINT NOT NULL DEFAULT 0 CHECK(support_units >= 0),
    active_ms BIGINT NOT NULL DEFAULT 0 CHECK(active_ms >= 0),
    tie_break_at TIMESTAMPTZ,
    PRIMARY KEY(encounter_id, player_id)
);

CREATE TABLE IF NOT EXISTS reward_claim_scopes (
    scope_key TEXT NOT NULL,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    grant_id UUID NOT NULL REFERENCES reward_grants(grant_id),
    PRIMARY KEY(scope_key, player_id)
);

CREATE TABLE IF NOT EXISTS workspaces (
    workspace_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL UNIQUE REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    size_tier INTEGER NOT NULL CHECK(size_tier > 0),
    layout_version BIGINT NOT NULL DEFAULT 0,
    layout JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS workspace_acl (
    workspace_id UUID NOT NULL REFERENCES workspaces(workspace_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    permission TEXT NOT NULL CHECK(permission IN ('VISIT','USE_STATION','STORAGE')),
    PRIMARY KEY(workspace_id, player_id, permission)
);

CREATE TABLE IF NOT EXISTS workspace_generators (
    generator_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(workspace_id) ON DELETE CASCADE,
    definition_id TEXT NOT NULL,
    definition_version INTEGER NOT NULL DEFAULT 1,
    placed_x INTEGER NOT NULL,
    placed_y INTEGER NOT NULL,
    placed_z INTEGER NOT NULL,
    last_collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accumulated_amount BIGINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS processing_jobs (
    job_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspaces(workspace_id) ON DELETE CASCADE,
    recipe_id TEXT NOT NULL,
    recipe_version INTEGER NOT NULL DEFAULT 1,
    operation_id UUID NOT NULL UNIQUE REFERENCES operations(operation_id),
    input_escrow JSONB NOT NULL,
    output_spec JSONB NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('RUNNING','READY','CLAIMED','CANCELLED')),
    started_at TIMESTAMPTZ NOT NULL,
    finishes_at TIMESTAMPTZ NOT NULL,
    output_grant_id UUID UNIQUE REFERENCES reward_grants(grant_id),
    CHECK(finishes_at >= started_at)
);

CREATE INDEX IF NOT EXISTS jobs_due ON processing_jobs(finishes_at) WHERE state = 'RUNNING';

CREATE TABLE IF NOT EXISTS market_listings (
    listing_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    escrow_location_id UUID NOT NULL REFERENCES asset_locations(location_id),
    gross_price BIGINT NOT NULL CHECK(gross_price > 0),
    tax_basis_points INTEGER NOT NULL CHECK(tax_basis_points BETWEEN 0 AND 10000),
    state TEXT NOT NULL CHECK(state IN ('ACTIVE','SOLD','CANCELLED','EXPIRED')),
    buyer_id UUID REFERENCES user_accounts(account_id),
    buyer_grant_id UUID REFERENCES reward_grants(grant_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK(buyer_id IS NULL OR buyer_id <> seller_id),
    CHECK((state = 'SOLD') = (buyer_id IS NOT NULL)),
    CHECK(expires_at > created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS one_active_escrow_listing ON market_listings(escrow_location_id) WHERE state = 'ACTIVE';
CREATE INDEX IF NOT EXISTS market_browse ON market_listings(created_at, listing_id) WHERE state = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- 7. Messaging, Auditing & Authentication Keys
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES operations(operation_id),
    event_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id TEXT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS outbox_pending ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_events (
    consumer TEXT NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(consumer, event_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id UUID PRIMARY KEY,
    operation_id UUID REFERENCES operations(operation_id),
    actor_principal TEXT NOT NULL,
    action TEXT NOT NULL,
    target_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    before_state JSONB,
    after_state JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS web_login_keys (
    key_code TEXT PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES user_accounts(account_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_web_keys_player ON web_login_keys(player_id, expires_at);

CREATE TABLE IF NOT EXISTS server_shop_products (
    product_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    description TEXT,
    price_coin BIGINT NOT NULL CHECK(price_coin >= 0),
    items JSONB NOT NULL DEFAULT '[]',
    is_active BOOLEAN NOT NULL DEFAULT true,
    time_limited_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_shop_active ON server_shop_products(is_active, time_limited_until);

COMMIT;


-- -----------------------------------------------------------------------------
-- Review fixes (Task.md R36). Same content as migrations/2026-11-12_r36_constraints.sql
-- -----------------------------------------------------------------------------
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
